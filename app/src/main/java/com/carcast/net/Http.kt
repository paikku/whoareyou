package com.carcast.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** Minimal HTTP/1.1 request parser: enough for static files and the WebSocket upgrade. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val isWebSocketUpgrade: Boolean
        get() = header("upgrade")?.equals("websocket", ignoreCase = true) == true &&
            header("sec-websocket-key") != null

    companion object {
        fun parse(input: BufferedInputStream): HttpRequest? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null
            val target = parts[1]
            val qIdx = target.indexOf('?')
            val path = if (qIdx >= 0) target.substring(0, qIdx) else target
            val query = if (qIdx >= 0) parseQuery(target.substring(qIdx + 1)) else emptyMap()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return null
                if (line.isEmpty()) break
                val c = line.indexOf(':')
                if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
            }
            return HttpRequest(parts[0], path, query, headers)
        }

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
                if (buf.size() > 16 * 1024) return null
            }
            return buf.toString(Charsets.ISO_8859_1.name())
        }

        private fun parseQuery(q: String): Map<String, String> =
            q.split('&').filter { it.isNotEmpty() }.associate {
                val i = it.indexOf('=')
                val k = if (i >= 0) it.substring(0, i) else it
                val v = if (i >= 0) it.substring(i + 1) else ""
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
    }
}

object HttpResponse {
    fun write(out: OutputStream, status: Int, reason: String, contentType: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    fun mimeFor(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }
}
