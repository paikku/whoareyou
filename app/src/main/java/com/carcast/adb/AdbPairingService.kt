package com.carcast.adb

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.carcast.CarCastApp
import com.carcast.R
import com.carcast.service.StreamService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-time pairing with this phone's own adbd, done the Shizuku way: the user opens
 * Developer options → Wireless debugging → "Pair device with pairing code", and types the 6-digit
 * code into this service's notification (RemoteInput), so the dialog stays on screen while we pair.
 * The pairing port is discovered over mDNS while that dialog is open; it can also be typed
 * (EXTRA_PORT) when discovery fails, since the dialog shows "IP address & port".
 */
class AdbPairingService : Service() {
    private var mdns: AdbMdns? = null
    @Volatile private var port = 0
    private val portFound = CountDownLatch(1)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
                    ?: intent.getStringExtra(KEY_CODE) ?: ""
                val manualPort = intent.getIntExtra(EXTRA_PORT, 0)
                startForeground(NOTIF_ID, notification(getString(R.string.pair_working), input = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                ensureDiscovery()
                Thread({ pair(code, manualPort) }, "adb-pair").apply { isDaemon = true }.start()
            }
            else -> {
                startForeground(NOTIF_ID, notification(getString(R.string.pair_prompt), input = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                ensureDiscovery()
                StreamService.log("페어링 대기: 무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 누르고 알림에 코드를 입력하세요")
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureDiscovery() {
        if (mdns != null) return
        mdns = AdbMdns(this, AdbMdns.PAIRING, requireLocal = false) { p, host, local ->
            port = p
            portFound.countDown()
            StreamService.log("페어링 포트 발견: $p (${host ?: "주소 없음"}${if (local) ", 이 폰" else ", 다른 기기"})")
        }.also { it.start() }
    }

    private fun pair(code: String, manualPort: Int) {
        val result = runCatching {
            val p = when {
                manualPort > 0 -> manualPort
                port > 0 -> port
                else -> {
                    StreamService.log("페어링 포트를 찾는 중 (mDNS, 최대 20초)…")
                    if (!portFound.await(20, TimeUnit.SECONDS)) error("페어링 포트를 찾지 못함 — 페어링 대화상자가 열려 있는지 확인하고, 안 되면 대화상자의 포트를 수동 입력")
                    port
                }
            }
            StreamService.log("페어링 시도: 127.0.0.1:$p 코드 ${code.filter { it.isDigit() }.replace(Regex("\\d"), "*")}")
            AdbLink.pair(p, code)
        }
        val prefs = AdbPrefs(this)
        result.onSuccess {
            prefs.paired = true
            StreamService.log("페어링 성공 — 지문 ${AdbIdentity.fingerprint()?.take(16)}…")
            notify(notification(getString(R.string.pair_done), input = false))
            // The stream service, if running, was waiting for this: tell it to connect now.
            if (StreamService.running) startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_CONNECT))
            android.os.Handler(mainLooper).postDelayed({ stopSelf() }, 3000)
        }.onFailure { e ->
            val why = when {
                (e.message ?: "").contains("ECONNREFUSED") -> "페어링 창이 닫혀 있음 — 창을 다시 열고, 창을 띄운 채 알림창으로만 코드를 입력하세요"
                e is IllegalArgumentException -> e.message ?: "잘못된 코드"
                (e.message ?: "").contains("PairAuth") || (e.message ?: "").contains("Pairing") -> "코드가 틀렸거나 만료됨 — 창을 다시 열어 새 코드로"
                else -> e.message ?: e.toString()
            }
            StreamService.log("페어링 실패: $why")
            notify(notification(getString(R.string.pair_failed, why), input = true))
        }
    }

    private fun notify(n: Notification) = getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, n)

    private fun notification(text: String, input: Boolean): Notification {
        val b = NotificationCompat.Builder(this, CarCastApp.PAIRING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.pair_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(this, 0, AdbPrefs.wirelessDebuggingIntent(), PendingIntent.FLAG_IMMUTABLE))
        if (input) {
            val remote = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.pair_code)).build()
            val reply = PendingIntent.getService(
                this, 1, Intent(this, AdbPairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(NotificationCompat.Action.Builder(0, getString(R.string.pair_enter), reply).addRemoteInput(remote).build())
        }
        b.addAction(0, getString(R.string.stop), PendingIntent.getService(this, 2, Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        return b.build()
    }

    override fun onDestroy() {
        mdns?.stop(); mdns = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2
        const val ACTION_CODE = "com.carcast.adb.PAIR_CODE"
        const val ACTION_STOP = "com.carcast.adb.PAIR_STOP"
        const val KEY_CODE = "code"
        const val EXTRA_PORT = "port"
    }
}
