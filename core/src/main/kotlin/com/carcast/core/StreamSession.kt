package com.carcast.core

import com.carcast.core.media.ClipSource
import com.carcast.core.media.MediaHub
import com.carcast.core.net.HttpServer
import com.carcast.core.net.WebSocketConnection
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
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
    /** Where diagnostic reports from the car are kept across restarts; null keeps them in memory only. */
    reportDir: File? = null,
) {
    val reports = ReportStore(reportDir)
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

    /**
     * Called when loopback asks for `POST /api/stop`: the app's kill switch for a detached shell
     * server (wireless debugging is off whenever Wi-Fi is, so adb cannot be relied on to kill it).
     * Only 127.x may ask; the car or anyone on the hotspot cannot.
     */
    var onStopRequest: () -> Unit = {}

    private fun event(s: String) { Log.i(TAG, s); onEvent(s) }

    @Throws(IOException::class)
    fun start() {
        if (running) return
        val server = HttpServer(assets, port, ::onWebSocket, ::onApi)
        // The app polls /api/status over loopback every 2 s; only remote (car/laptop) connections are events.
        server.onAccept = { remote, local ->
            if (remote.startsWith("127.")) Log.d(TAG, "accept $remote → $local") else event("accept $remote → $local")
        }
        server.start()
        http = server
        running = true
        event("HTTP 서버 시작 ($process): 0.0.0.0:$port" + if (reports.size > 0) ", 저장된 진단 ${reports.size}건" else "")
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

    private fun onApi(method: String, path: String, query: Map<String, String>, body: ByteArray, remote: String): String? = when {
        path == "/api/status" -> statusJson()
        path == "/api/reports" && method == "GET" -> reports.listJson(query["limit"]?.toIntOrNull() ?: ReportStore.MAX)
        path == "/api/log" && method == "GET" -> Json.array(Log.recentLines().takeLast(query["limit"]?.toIntOrNull() ?: Log.RECENT_MAX))
        path == "/api/stop" && method == "POST" -> {
            if (!remote.startsWith("127.")) Json.obj(mapOf("ok" to false, "error" to "loopback only"))
            else { event("종료 요청 ($remote)"); Thread({ Thread.sleep(200); onStopRequest() }, "stop").apply { isDaemon = true }.start(); Json.obj(mapOf("ok" to true)) }
        }
        path == "/api/report" && method == "POST" -> {
            val r = reports.add(String(body, Charsets.UTF_8), remote)
            if (r == null) Json.obj(mapOf("ok" to false, "error" to "body is not a JSON object"))
            else {
                event("진단 결과 #${r.id} 수신 ($remote): ${r.summary}")
                Json.obj(mapOf("ok" to true, "id" to r.id, "receivedAt" to r.receivedAt, "stored" to reports.size))
            }
        }
        else -> null
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
            "addresses" to localAddresses(),
            "reports" to reports.size,
            "lastReport" to reports.last?.let { mapOf("id" to it.id, "receivedAt" to it.receivedAt, "remote" to it.remote, "summary" to it.summary) },
        )
        fields.putAll(extraStatus())
        return Json.obj(fields)
    }

    /**
     * Non-loopback IPv4 addresses of this host, "iface=addr". The diag page uses them as the control
     * group: the car must NOT be able to open http://<hotspot address>:port, only the tun address.
     * Empty when the process may not enumerate interfaces (app uid under SELinux).
     */
    private fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${ni.name}=${it.hostAddress}" } }
    } catch (_: Exception) { emptyList() }

    companion object {
        private const val TAG = "StreamSession"
        const val TEST_CLIP = "test-720p30.cmp4"
    }
}

/** Just enough JSON to write flat status objects without pulling a library into the shell process. */
object Json {
    /** Pre-serialized JSON to embed verbatim (validate with [isObject] first). */
    class Raw(val json: String)

    fun obj(fields: Map<String, Any?>): String = fields.entries.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }
    fun array(items: Iterable<Any?>): String = items.joinToString(",", "[", "]") { value(it) }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is Raw -> v.json
        is Map<*, *> -> obj(v.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> str(v.toString())
    }

    /**
     * Structural check that [s] is one complete JSON object: balanced braces/brackets outside strings,
     * nothing after the closing brace. Enough to embed untrusted client text into our own output
     * without a parser; it does not validate scalars.
     */
    fun isObject(s: String): Boolean {
        if (!s.startsWith("{")) return false
        var depth = 0
        var inString = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                    '\n', '\r' -> return false
                }
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return i == s.length - 1; if (depth < 0) return false }
            }
            i++
        }
        return false
    }

    /** Reverses [str] for the simple escapes it produces (and \uXXXX). */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> if (i + 5 < s.length) { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 } else sb.append(e)
                    else -> sb.append(e)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
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
