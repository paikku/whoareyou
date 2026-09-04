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
    private val statusJson: () -> String,
) {
    fun interface WsHandler {
        /** Called on a fresh connection; return false to reject (404). */
        fun onWebSocket(path: String, query: Map<String, String>, conn: WebSocketConnection): Boolean
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "http").apply { isDaemon = true } }
    @Volatile private var running = false
    /** Called once per accepted TCP connection (after the 3-way handshake) with remote and local addresses. */
    var onAccept: ((remote: String, local: String) -> Unit)? = null

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
        while (running) {
            val client = try { s.accept() } catch (e: IOException) { if (running) Log.w(TAG, "accept: $e"); break }
            onAccept?.invoke("${client.inetAddress.hostAddress}:${client.port}", "${client.localAddress.hostAddress}:${client.localPort}")
            pool.execute { handle(client) }
        }
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
                req.method != "GET" && req.method != "HEAD" ->
                    HttpResponse.write(output, 405, "Method Not Allowed", "text/plain", "405".toByteArray())
                req.path == "/api/status" ->
                    HttpResponse.write(output, 200, "OK", "application/json; charset=utf-8", statusJson().toByteArray())
                else -> serveStatic(req.path, output)
            }
            socket.close()
        } catch (e: IOException) {
            Log.d(TAG, "connection: $e")
            try { socket.close() } catch (_: IOException) {}
        }
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
    }
}
