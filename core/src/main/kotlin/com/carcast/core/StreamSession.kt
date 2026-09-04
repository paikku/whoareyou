package com.carcast.core

import com.carcast.core.media.ClipSource
import com.carcast.core.media.MediaHub
import com.carcast.core.net.HttpServer
import com.carcast.core.net.WebSocketConnection
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One streaming session: HTTP/WS server, video and audio fan-out, control channel and (until the
 * screen capture exists) the bundled test clip as the video source. Process-agnostic: the shell-uid
 * server and the app's optional in-process server both drive this class.
 */
class StreamSession(
    private val assets: Assets,
    val port: Int,
    /** Where this session runs, reported in /api/status: "shell" or "app". */
    private val process: String,
    /** Extra fields for /api/status (e.g. tun state), evaluated on each request. */
    private val extraStatus: () -> Map<String, Any?> = { emptyMap() },
) {
    private var http: HttpServer? = null
    private val videoHub = MediaHub()
    private val audioHub = MediaHub()
    private var clip: ClipSource? = null
    private val controlClients = CopyOnWriteArrayList<WebSocketConnection>()

    @Volatile var running = false
        private set
    @Volatile var controlPackets = 0L
        private set
    val videoClients: Int get() = videoHub.clientCount
    val controlClientCount: Int get() = controlClients.size

    /** Human-readable events (also logged); the app shows them on screen, the shell prints them. */
    var onEvent: (String) -> Unit = {}

    private fun event(s: String) { Log.i(TAG, s); onEvent(s) }

    @Throws(IOException::class)
    fun start() {
        if (running) return
        val server = HttpServer(assets, port, ::onWebSocket, ::statusJson)
        // The app polls /api/status over loopback every 2 s; only remote (car/laptop) connections are events.
        server.onAccept = { remote, local ->
            if (remote.startsWith("127.")) Log.d(TAG, "accept $remote → $local") else event("accept $remote → $local")
        }
        server.start()
        http = server
        running = true
        event("HTTP 서버 시작 ($process): 0.0.0.0:$port")
        if (assets.exists("clips/$TEST_CLIP")) {
            clip = ClipSource(assets, "clips/$TEST_CLIP", videoHub).also { it.start() }
            event("테스트 클립 송출: $TEST_CLIP")
        } else {
            event("테스트 클립 없음 (assets/clips/$TEST_CLIP)")
        }
    }

    fun stop() {
        if (!running) return
        clip?.stop(); clip = null
        videoHub.closeAll()
        audioHub.closeAll()
        for (c in controlClients) c.close()
        controlClients.clear()
        http?.stop(); http = null
        running = false
        event("세션 종료")
    }

    private fun onWebSocket(path: String, query: Map<String, String>, conn: WebSocketConnection): Boolean {
        return when (path) {
            "/ws/video" -> { videoHub.attach(conn); event("video 클라이언트 접속 (${videoHub.clientCount})"); true }
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
        // M5 wires this into the input injector. For now count it so /diag and the UI show activity.
        if (data.isNotEmpty()) {
            controlPackets++
            if (controlPackets % 50 == 1L) event("control 패킷 ${controlPackets}개 (kind=${data[0]})")
        }
    }

    fun statusJson(): String {
        val fields = linkedMapOf<String, Any?>(
            "type" to "status",
            "running" to running,
            "process" to process,
            "port" to port,
            "videoClients" to videoHub.clientCount,
            "controlClients" to controlClients.size,
            "controlPackets" to controlPackets,
            "source" to if (clip != null) "clip" else "none",
            "width" to 1280,
            "height" to 720,
        )
        fields.putAll(extraStatus())
        return Json.obj(fields)
    }

    companion object {
        private const val TAG = "StreamSession"
        const val TEST_CLIP = "test-720p30.cmp4"
    }
}

/** Just enough JSON to write flat status objects without pulling a library into the shell process. */
object Json {
    fun obj(fields: Map<String, Any?>): String = fields.entries.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is Map<*, *> -> obj(v.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> str(v.toString())
    }

    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
