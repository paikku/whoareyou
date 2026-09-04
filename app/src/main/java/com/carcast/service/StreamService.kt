package com.carcast.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.carcast.CarCastApp
import com.carcast.Config
import com.carcast.R
import com.carcast.net.HttpServer
import com.carcast.net.WebSocketConnection
import com.carcast.ui.MainActivity
import com.carcast.vpn.CarVpnService
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground service that owns the session: tun address (via CarVpnService), HTTP/WS server,
 * media fan-out and (from M4) the shell server link. M1/M2: streams the bundled test clip.
 */
class StreamService : Service() {

    private var http: HttpServer? = null
    private val videoHub = MediaHub()
    private val audioHub = MediaHub()
    private var clip: ClipSource? = null
    private val controlClients = CopyOnWriteArrayList<WebSocketConnection>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            stopSelf()
            return START_NOT_STICKY
        }
        useVpn = intent?.getBooleanExtra(EXTRA_USE_VPN, true) ?: true
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        startSession()
        return START_STICKY
    }

    private fun startSession() {
        if (http != null) return
        if (useVpn) startService(Intent(this, CarVpnService::class.java)) else log("VPN 없이 시작 (핫스팟 주소로만 접속 가능)")
        val server = HttpServer(assets, Config.HTTP_PORT, ::onWebSocket, ::statusJson)
        server.onAccept = { remote, local -> log("accept $remote → $local") }
        try {
            server.start()
            http = server
            running = true
            log("HTTP 서버 시작: http://${Config.TUN_ADDRESS}:${Config.HTTP_PORT} (빌드 ${com.carcast.BuildConfig.GIT_SHA})")
            // If the tun came up before us (service restart), protect the fresh listener now.
            CarVpnService.instance?.takeIf { CarVpnService.state == CarVpnService.State.UP }?.let { protectListener(it) }
        } catch (e: IOException) {
            log("HTTP 서버 실패: $e")
            return
        }
        // Until the shell server exists (M4), stream the bundled test clip if present.
        if (assets.list("clips")?.contains(TEST_CLIP) == true) {
            clip = ClipSource(assets, "clips/$TEST_CLIP", videoHub).also { it.start() }
            log("테스트 클립 송출: $TEST_CLIP")
        } else {
            log("테스트 클립 없음 (assets/clips/$TEST_CLIP)")
        }
    }

    /** Called by CarVpnService once the tun is up; see HttpServer.protectWith. */
    fun protectListener(vpn: VpnService) {
        val ok = http?.protectWith(vpn) ?: false
        log("리스너 VPN 보호(protect) → $ok")
    }

    private fun stopSession() {
        clip?.stop(); clip = null
        videoHub.closeAll()
        audioHub.closeAll()
        for (c in controlClients) c.close()
        controlClients.clear()
        http?.stop(); http = null
        startService(Intent(this, CarVpnService::class.java).setAction(CarVpnService.ACTION_STOP))
        running = false
        log("세션 종료")
    }

    private fun onWebSocket(path: String, query: Map<String, String>, conn: WebSocketConnection): Boolean {
        return when (path) {
            "/ws/video" -> { videoHub.attach(conn); log("video 클라이언트 접속 (${videoHub.clientCount})"); true }
            "/ws/audio" -> { audioHub.attach(conn); true }
            "/ws/control" -> {
                controlClients += conn
                conn.listener = object : WebSocketConnection.Listener {
                    override fun onBinary(conn: WebSocketConnection, data: ByteArray) { onControl(data) }
                    override fun onText(conn: WebSocketConnection, text: String) {}
                    override fun onClose(conn: WebSocketConnection) { controlClients.remove(conn) }
                }
                conn.sendText(statusJson())
                true
            }
            else -> false
        }
    }

    private fun onControl(data: ByteArray) {
        // M5 wires this into the shell server. For now count it so /diag and the UI show activity.
        if (data.isNotEmpty()) {
            controlPackets++
            if (controlPackets % 50 == 1L) log("control 패킷 ${controlPackets}개 (kind=${data[0]})")
        }
    }

    private fun statusJson(): String = JSONObject().apply {
        put("type", "status")
        put("running", running)
        put("vpn", CarVpnService.state.name)
        put("address", Config.TUN_ADDRESS)
        put("port", Config.HTTP_PORT)
        put("videoClients", videoHub.clientCount)
        put("controlClients", controlClients.size)
        put("controlPackets", controlPackets)
        put("source", if (clip != null) "clip" else "none")
        put("width", 1280)
        put("height", 720)
    }.toString()

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
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StreamService"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.carcast.service.STOP"
        const val EXTRA_USE_VPN = "useVpn"
        @Volatile var useVpn = true
            private set
        const val TEST_CLIP = "test-720p30.cmp4"

        @Volatile var instance: StreamService? = null
            private set
        @Volatile var running = false
            private set
        @Volatile var controlPackets = 0L
            private set

        /** Simple in-memory log the activity polls; good enough until a real log view exists. */
        val logLines = java.util.concurrent.ConcurrentLinkedDeque<String>()
        fun log(s: String) {
            Log.i(TAG, s)
            logLines.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $s")
            while (logLines.size > 200) logLines.pollFirst()
        }
    }
}
