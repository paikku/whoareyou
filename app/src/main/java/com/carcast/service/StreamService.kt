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
import com.carcast.core.Assets
import com.carcast.core.StreamSession
import com.carcast.ui.MainActivity
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            stopSelf()
            return START_NOT_STICKY
        }
        useVpn = intent?.getBooleanExtra(EXTRA_USE_VPN, true) ?: true
        serverInApp = intent?.getBooleanExtra(EXTRA_SERVER_IN_APP, false) ?: false
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        startSession()
        return START_STICKY
    }

    private fun startSession() {
        if (running) return
        running = true
        if (useVpn) startService(Intent(this, CarVpnService::class.java)) else log("VPN 없이 시작 (핫스팟 주소로만 접속 가능)")
        if (serverInApp) {
            val s = StreamSession(AssetManagerAssets(assets), Config.HTTP_PORT, "app") { mapOf("vpn" to CarVpnService.state.name, "address" to Config.TUN_ADDRESS) }
            s.onEvent = ::log
            try {
                s.start(); inApp = s
            } catch (e: IOException) {
                log("앱 내장 서버 실패: $e (shell 서버가 이미 3333을 쓰고 있나요?)")
            }
        } else {
            log("shell 서버를 기다리는 중 — PC에서: ${shellCommand()}")
        }
        watcher = Thread({ watchShellServer() }, "shell-watch").apply { isDaemon = true; start() }
    }

    private fun stopSession() {
        if (!running) return
        running = false
        watcher?.interrupt(); watcher = null
        inApp?.stop(); inApp = null
        startService(Intent(this, CarVpnService::class.java).setAction(CarVpnService.ACTION_STOP))
        shellStatus = null
        log("세션 종료")
    }

    /** Polls the local port so the screen shows whether the shell server (or the in-app one) is answering. */
    private fun watchShellServer() {
        var wasUp = false
        while (running && !Thread.currentThread().isInterrupted) {
            val s = try {
                val c = URL("http://127.0.0.1:${Config.HTTP_PORT}/api/status").openConnection() as HttpURLConnection
                c.connectTimeout = 1000; c.readTimeout = 1000
                c.inputStream.use { String(it.readBytes()) }
            } catch (_: Exception) { null }
            shellStatus = s
            val up = s != null
            if (up != wasUp) log(if (up) "서버 응답 확인: ${s?.take(120)}" else "서버 응답 없음 (127.0.0.1:${Config.HTTP_PORT})")
            wasUp = up
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CarCastApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, Config.TUN_ADDRESS, Config.HTTP_PORT))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopSession()
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

        /** The exact command to start the shell server from a PC until the app launches it itself (M3). */
        fun shellCommand(): String =
            "adb shell 'CLASSPATH=\$(pm path com.carcast | cut -d: -f2) app_process / com.carcast.server.Server ${BuildConfig.GIT_SHA} port=${Config.HTTP_PORT}'"

        /** Simple in-memory log the activity polls; good enough until a real log view exists. */
        val logLines = java.util.concurrent.ConcurrentLinkedDeque<String>()
        fun log(s: String) {
            Log.i(TAG, s)
            logLines.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $s")
            while (logLines.size > 200) logLines.pollFirst()
        }
    }
}
