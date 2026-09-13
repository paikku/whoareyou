package com.carcast.core.net

import com.carcast.core.Assets
import com.carcast.core.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Serves the bundled web client (assets "web/...") and upgrades the WebSocket paths.
 * Bound on 0.0.0.0 so the tun address (100.99.9.9), the hotspot address and loopback all work.
 *
 * Runs in the shell-uid process in production: Android 14+ drops packets addressed to a VPN's
 * address that arrive on any other interface, but only for sockets owned by app uids
 * (netd's ingress_discard_map is skipped for uids below 10000). See docs/dev-plan.md.
 */
class HttpServer(
    private val assets: Assets,
    private val port: Int,
    private val wsHandler: WsHandler,
    private val api: ApiHandler,
) {
    fun interface WsHandler {
        /** Called on a fresh connection; return false to reject (404). */
        fun onWebSocket(path: String, query: Map<String, String>, conn: WebSocketConnection): Boolean
    }

    /** JSON endpoints under /api. Return the response body, or null for 404. [body] is empty for GET. */
    fun interface ApiHandler {
        fun handle(method: String, path: String, query: Map<String, String>, body: ByteArray, remote: String): String?
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "http").apply { isDaemon = true } }
    @Volatile private var running = false
    /** Called once per accepted TCP connection (after the 3-way handshake) with remote and local addresses. */
    var onAccept: ((remote: String, local: String) -> Unit)? = null

    /**
     * Connections accepted, errors accept() threw, and when the last one arrived. These are in
     * /api/status for one reason: when the car says "it died", they say whether the car's connections
     * were reaching the phone at all. Without them a dropped Wi-Fi link and a wedged server read
     * exactly the same from the browser — both are "it stopped answering".
     */
    @Volatile var accepts = 0L
        private set
    @Volatile var acceptErrors = 0L
        private set
    @Volatile var lastAcceptAt = 0L
        private set
    /** False once the accept loop is gone: the process lives but nobody can connect any more. */
    @Volatile var accepting = false
        private set

    @Throws(IOException::class)
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress("0.0.0.0", port), 16)
        server = s
        running = true
        Thread({ acceptLoop(s) }, "http-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on 0.0.0.0:$port")
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        server = null
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        accepting = true
        while (running) {
            val client = try { s.accept() } catch (e: IOException) {
                // This used to end the loop on any IOException, and that is a way for the server to die
                // without exiting: accept() also throws for reasons that pass (EMFILE when too many
                // sockets are open at once), and after one of those the process kept running, kept
                // answering the sockets it already had, and never accepted another connection again —
                // the shape of real-car reports #31-33. Only a closed socket ends the loop now.
                if (!running || s.isClosed) break
                acceptErrors++
                Log.w(TAG, "accept: $e (${acceptErrors}회) — 계속 받는다")
                try { Thread.sleep(ACCEPT_RETRY_MS) } catch (_: InterruptedException) { break }
                continue
            }
            accepts++
            lastAcceptAt = System.currentTimeMillis()
            onAccept?.invoke("${client.inetAddress.hostAddress}:${client.port}", "${client.localAddress.hostAddress}:${client.localPort}")
            pool.execute { handle(client) }
        }
        accepting = false
        Log.i(TAG, "accept loop ended after $accepts connections")
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val req = HttpRequest.parse(input) ?: run { socket.close(); return }

            if (req.isWebSocketUpgrade) {
                socket.soTimeout = 0
                WebSocketConnection.handshake(req, output)
                val conn = WebSocketConnection(socket, input, output, queueCapacity = 64)
                if (!wsHandler.onWebSocket(req.path, req.query, conn)) {
                    conn.close()
                    return
                }
                conn.start()
                return // the connection's own threads take over the socket
            }

            when {
                req.path.startsWith("/api/") -> serveApi(req, input, output, "${socket.inetAddress.hostAddress}:${socket.port}")
                req.method != "GET" && req.method != "HEAD" ->
                    HttpResponse.write(output, 405, "Method Not Allowed", "text/plain", "405".toByteArray())
                else -> serveStatic(req.path, output)
            }
            socket.close()
        } catch (e: IOException) {
            Log.d(TAG, "connection: $e")
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun serveApi(req: HttpRequest, input: BufferedInputStream, output: BufferedOutputStream, remote: String) {
        val length = req.header("content-length")?.toIntOrNull() ?: 0
        if (length > MAX_BODY) {
            HttpResponse.write(output, 413, "Payload Too Large", "text/plain", "413".toByteArray()); return
        }
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) throw IOException("body truncated at $off/$length")
            off += n
        }
        val json = api.handle(req.method, req.path, req.query, body, remote)
        if (json == null) {
            HttpResponse.write(output, 404, "Not Found", "text/plain", "404 ${req.path}".toByteArray()); return
        }
        HttpResponse.write(output, 200, "OK", "application/json; charset=utf-8", json.toByteArray())
    }

    private fun serveStatic(rawPath: String, output: BufferedOutputStream) {
        var path = rawPath.trimStart('/')
        if (path.isEmpty()) path = "index.html"
        if (path == "diag") path = "diag.html"
        if (path.contains("..")) {
            HttpResponse.write(output, 400, "Bad Request", "text/plain", "400".toByteArray()); return
        }
        val body = assets.open("web/$path")?.use { it.readBytes() }
        if (body == null) {
            HttpResponse.write(output, 404, "Not Found", "text/plain", "404 $path".toByteArray()); return
        }
        HttpResponse.write(output, 200, "OK", HttpResponse.mimeFor(path), body)
    }

    companion object {
        private const val TAG = "HttpServer"
        /** Diagnostic reports from the car are a few KB; anything larger is not ours. */
        const val MAX_BODY = 256 * 1024
        /** Breathing room after a failed accept, so a permanent failure cannot become a hot loop. */
        private const val ACCEPT_RETRY_MS = 100L
    }
}
