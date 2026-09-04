package com.carcast.net

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import android.net.VpnService
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Serves the bundled web client from assets/web and upgrades the /ws/ paths to WebSocket.
 * Bound on 0.0.0.0 so the tun address (100.99.9.9), the hotspot address and loopback all work.
 */
class HttpServer(
    private val assets: AssetManager,
    private val port: Int,
    private val wsHandler: WsHandler,
    private val statusJson: () -> String,
) {
    fun interface WsHandler {
        /** Called on a fresh connection; return false to reject (404). */
        fun onWebSocket(path: String, query: Map<String, String>, conn: WebSocketConnection): Boolean
    }

    private var server: TcpListener? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "http").apply { isDaemon = true } }
    @Volatile private var running = false
    /** Called once per accepted TCP connection (after the 3-way handshake) with remote and local addresses. */
    var onAccept: ((remote: String, local: String) -> Unit)? = null

    @Throws(IOException::class)
    fun start() {
        val s = TcpListener(port)
        server = s
        running = true
        Thread({ acceptLoop(s) }, "http-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on 0.0.0.0:$port")
    }

    fun stop() {
        running = false
        server?.close()
        server = null
        pool.shutdownNow()
    }

    /**
     * Marks the listening socket "protected from VPN" so the kernel routes our SYN-ACKs and every
     * accepted connection like a non-VPN app's traffic even while our own tun is up. Must be called
     * by the VpnService that owns the tun. Returns false when there is no listener or protect() failed.
     */
    fun protectWith(vpn: VpnService): Boolean {
        val s = server ?: return false
        return try { s.withRawFd { vpn.protect(it) } } catch (e: Exception) { Log.w(TAG, "protect failed", e); false }
    }

    private fun acceptLoop(s: TcpListener) {
        while (running) {
            val client = try { s.accept() } catch (e: IOException) { if (running) Log.w(TAG, "accept: $e"); break }
            onAccept?.invoke(client.remote, client.local)
            pool.execute { handle(client) }
        }
    }

    private fun handle(socket: TcpConn) {
        try {
            socket.setTcpNoDelay(true)
            socket.readTimeoutMs = 15_000
            val input = BufferedInputStream(socket.input, 8192)
            val output = BufferedOutputStream(socket.output, 64 * 1024)
            val req = HttpRequest.parse(input) ?: run { socket.close(); return }

            if (req.isWebSocketUpgrade) {
                socket.readTimeoutMs = 0
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
            socket.close()
        }
    }

    private fun serveStatic(rawPath: String, output: BufferedOutputStream) {
        var path = rawPath.trimStart('/')
        if (path.isEmpty()) path = "index.html"
        if (path == "diag") path = "diag.html"
        if (path.contains("..")) {
            HttpResponse.write(output, 400, "Bad Request", "text/plain", "400".toByteArray()); return
        }
        val body = try {
            assets.open("web/$path").use { it.readBytes() }
        } catch (_: IOException) {
            HttpResponse.write(output, 404, "Not Found", "text/plain", "404 $path".toByteArray()); return
        }
        HttpResponse.write(output, 200, "OK", HttpResponse.mimeFor(path), body)
    }

    companion object {
        private const val TAG = "HttpServer"
    }
}
