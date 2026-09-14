package com.carcast.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.AssetManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.carcast.BuildConfig
import com.carcast.CarCastApp
import com.carcast.Config
import com.carcast.R
import com.carcast.adb.ServerCommand
import com.carcast.adb.ShellServerLink
import com.carcast.core.Assets
import com.carcast.core.StreamSession
import com.carcast.ui.MainActivity
import com.carcast.widget.CarCastWidget
import com.carcast.vpn.CarVpnService
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground service that owns the phone-side session: the tun address (via CarVpnService) and,
 * once M3 lands, the ADB link that launches the shell-uid server. The HTTP/WS server itself runs
 * in the shell process (see docs/dev-plan.md: Android 14+ drops hotspot→VPN-address TCP for app
 * uids). Until the ADB link exists the user starts that server from a PC with `adb shell`, and this
 * service watches http://127.0.0.1:3333/api/status to show whether it is up.
 *
 * For hotspot-address-only experiments the same session can run inside this process (EXTRA_SERVER_IN_APP).
 */
class StreamService : Service() {

    private var inApp: StreamSession? = null
    private var watcher: Thread? = null
    private var shellLink: ShellServerLink? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONNECT) {
            if (running) shellLink?.reconnect()
            return START_STICKY
        }
        if (intent?.action == ACTION_ALL_OFF) {
            // The press may come from the widget with no session running at all, so take the foreground
            // first: the sequence talks to the radio and can take tens of seconds, and a background service
            // would be killed halfway, leaving the hotspot up with nothing able to switch it off.
            startForeground(NOTIF_ID, buildNotification(getString(R.string.bulk_off_progress)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            runBulk {
                // A refused press (one already running) must not take the service down under the sequence
                // that is still running — it only puts the notification back.
                if (BulkControl.allOff(::log) { stopSession() }) stopSelf() else notifyRunning()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ALL_ON) {
            useVpn = intent.getBooleanExtra(EXTRA_USE_VPN, useVpn)
            serverInApp = intent.getBooleanExtra(EXTRA_SERVER_IN_APP, serverInApp)
            startForeground(NOTIF_ID, buildNotification(getString(R.string.bulk_on_progress)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            runBulk {
                BulkControl.allOn(this, ::log) { startSession() }
                // Back to the standing notification, whatever the sequence managed.
                notifyRunning()
            }

            return START_STICKY
        }
        useVpn = intent?.getBooleanExtra(EXTRA_USE_VPN, true) ?: true
        serverInApp = intent?.getBooleanExtra(EXTRA_SERVER_IN_APP, false) ?: false
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        startSession()
        return START_STICKY
    }

    /**
     * Runs a bulk sequence off the main thread. Whether it runs at all is [BulkControl]'s to decide — the
     * claim has to be atomic, and a check here would be a second, racier one.
     */
    private fun runBulk(body: () -> Unit) {
        Thread({
            try {
                body()
            } catch (t: Throwable) {
                log("일괄 동작 실패: $t")
            } finally {
                CarCastWidget.refresh(this)
            }
        }, "bulk").apply { isDaemon = true }.start()
    }

    private fun notifyRunning() {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun startSession() {
        if (running) return
        running = true
        CarCastWidget.refresh(this)
        if (useVpn) startService(Intent(this, CarVpnService::class.java)) else log("VPN 없이 시작 (핫스팟 주소로만 접속 가능)")
        if (serverInApp) {
            val s = StreamSession(
                AssetManagerAssets(assets), Config.HTTP_PORT, "app",
                extraStatus = { mapOf("vpn" to CarVpnService.state.name, "address" to Config.TUN_ADDRESS) },
                reportDir = java.io.File(filesDir, "reports"),
            )
            s.onEvent = ::log
            try {
                s.start(); inApp = s
            } catch (e: IOException) {
                log("앱 내장 서버 실패: $e (shell 서버가 이미 3333을 쓰고 있나요?)")
            }
        } else {
            // M3: the app itself connects to adbd (wireless debugging) and runs the server as uid 2000.
            // Until that succeeds the PC command stays on screen as the fallback.
            val link = ShellServerLink(this, ::log)
            link.onStateChange = { CarCastWidget.refresh(applicationContext) }
            shellLink = link
            link.start()
        }
        watcher = Thread({ watchShellServer() }, "shell-watch").apply { isDaemon = true; start() }
    }

    private fun stopSession() {
        if (!running) return
        running = false
        watcher?.interrupt(); watcher = null
        shellLink?.stop(); shellLink = null
        inApp?.stop(); inApp = null
        startService(Intent(this, CarVpnService::class.java).setAction(CarVpnService.ACTION_STOP))
        shellStatus = null
        CarCastWidget.refresh(this)
        log("세션 종료 (shell 서버는 그대로 둠 — 끄려면 '서버 종료')")
    }

    /** Polls the local port so the screen shows whether the shell server (or the in-app one) is answering. */
    private fun watchShellServer() {
        var wasUp = false
        while (running && !Thread.currentThread().isInterrupted) {
            val s = try { fetchLocal("/api/status") } catch (_: Exception) { null }
            shellStatus = s
            val up = s != null
            if (up != wasUp) log(
                if (up) {
                    val j = runCatching { org.json.JSONObject(s!!) }.getOrNull()
                    "서버 응답 확인: process=${j?.optString("process")} uid=${j?.opt("uid") ?: "?"} build=${j?.optString("build")} source=${j?.optString("source")}"
                } else "서버 응답 없음 (127.0.0.1:${Config.HTTP_PORT})"
            )
            // The home screen switch reads the same state, so push it the moment it changes rather than
            // letting the widget poll on its own (a widget has no process of its own to poll from).
            if (up != wasUp) CarCastWidget.refresh(this)
            wasUp = up
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    private fun buildNotification(text: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CarCastApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text ?: getString(R.string.notif_text, Config.TUN_ADDRESS, Config.HTTP_PORT))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The sequence has no context of its own; the widget is redrawn from here on every step it reports.
        BulkControl.onChange = { CarCastWidget.refresh(applicationContext) }
    }

    override fun onDestroy() {
        stopSession()
        BulkControl.onChange = null
        instance = null
        super.onDestroy()
    }

    /** Bundled assets through the app's AssetManager (the shell server reads the APK zip instead). */
    private class AssetManagerAssets(private val am: AssetManager) : Assets {
        override fun open(path: String): InputStream? = try { am.open(path) } catch (_: IOException) { null }
    }

    companion object {
        private const val TAG = "StreamService"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.carcast.service.STOP"
        /** Sent after pairing so the running session connects right away instead of waiting out its backoff. */
        const val ACTION_CONNECT = "com.carcast.service.CONNECT"
        /** One press for the lot — see [BulkControl] for why the order of the three steps is not a preference. */
        const val ACTION_ALL_ON = "com.carcast.service.ALL_ON"
        const val ACTION_ALL_OFF = "com.carcast.service.ALL_OFF"
        const val EXTRA_USE_VPN = "useVpn"
        const val EXTRA_SERVER_IN_APP = "serverInApp"

        @Volatile var useVpn = true
            private set
        @Volatile var serverInApp = false
            private set
        @Volatile var running = false
            private set
        /** Last /api/status body from 127.0.0.1:3333, null when nothing answers. */
        @Volatile var shellStatus: String? = null
            private set

        /** GET [path] from the local server (shell or in-app) over loopback; throws when nothing answers. */
        @Throws(IOException::class)
        fun fetchLocal(path: String, timeoutMs: Int = 1000): String {
            val c = URL("http://127.0.0.1:${Config.HTTP_PORT}$path").openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            return c.inputStream.use { String(it.readBytes()) }
        }

        /** The exact command to start the shell server from a PC when the app cannot (not paired, no wireless debugging). */
        fun shellCommand(): String = ServerCommand.forPc("com.carcast", BuildConfig.GIT_SHA, Config.HTTP_PORT)

        /** ADB link state for the screen: null when no session or the in-app server is used. */
        val linkState: String?
            get() = instance?.shellLink?.let { "${it.state}${if (it.detail.isNotEmpty()) " (${it.detail.take(60)})" else ""}" }
        /** The same, in one short line for the widget; null when there is no link. */
        val linkSummary: String?
            get() = instance?.shellLink?.summary()
        @Volatile private var instance: StreamService? = null

        /** Simple in-memory log the activity polls; good enough until a real log view exists. */
        val logLines = java.util.concurrent.ConcurrentLinkedDeque<String>()
        fun log(s: String) {
            Log.i(TAG, s)
            logLines.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $s")
            while (logLines.size > 200) logLines.pollFirst()
        }
    }
}
