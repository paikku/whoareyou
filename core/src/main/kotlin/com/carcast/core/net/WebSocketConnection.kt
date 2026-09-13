package com.carcast.core.net

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * RFC 6455 server-side connection. One writer thread per connection with a bounded queue:
 * when the car cannot keep up, [offer] fails and the caller decides what to drop (video keeps
 * dropping until the next keyframe). Frames from the client (touch/key) are delivered to [listener].
 */
class WebSocketConnection(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: OutputStream,
    private val queueCapacity: Int,
) {
    interface Listener {
        fun onBinary(conn: WebSocketConnection, data: ByteArray)
        fun onText(conn: WebSocketConnection, text: String)
        fun onClose(conn: WebSocketConnection)
    }

    private class Outgoing(val opcode: Int, val payload: ByteArray)

    private val queue = ArrayBlockingQueue<Outgoing>(queueCapacity)
    @Volatile private var closed = false
    @Volatile var listener: Listener? = null

    val queuedFrames: Int get() = queue.size
    /** "ip:port" of the peer, for status lines; the socket may already be gone. */
    val remote: String get() = runCatching { "${socket.inetAddress.hostAddress}:${socket.port}" }.getOrDefault("?")

    fun start() {
        Thread({ writeLoop() }, "ws-write").apply { isDaemon = true }.start()
        Thread({ readLoop() }, "ws-read").apply { isDaemon = true }.start()
    }

    /** Non-blocking; returns false when the queue is full. */
    fun offer(payload: ByteArray, text: Boolean = false): Boolean {
        if (closed) return false
        return queue.offer(Outgoing(if (text) OP_TEXT else OP_BINARY, payload))
    }

    /**
     * Enqueue something that must not be dropped (the init segment, a status line). Returns false when
     * this socket could not take it within [SEND_WAIT_MS] — and then closes it.
     *
     * This used to be an unbounded `queue.put`, and that is how the server died. A car whose TCP window
     * has closed (a Wi-Fi hiccup, the Tesla browser busy) stops draining; the queue fills; the next init
     * segment — and the encoder emits one on **every app start** — parked the encoder's output thread on
     * that one socket, holding EncodedH264Sink's monitor, until TCP finally gave up minutes later. The
     * picture froze for everybody while /api/status kept answering, which is exactly what "가끔 서버가
     * 죽는다" looks like from the car. A socket this far behind has nothing to gain from the wait anyway:
     * it is 64 frames late, so we drop it and let it reconnect — attach() hands a fresh client the init
     * segment and the last keyframe, which is a better picture than the one it was going to get.
     */
    fun send(payload: ByteArray, text: Boolean = false): Boolean {
        if (closed) return false
        val queued = try {
            queue.offer(Outgoing(if (text) OP_TEXT else OP_BINARY, payload), SEND_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!queued) close()
        return queued
    }

    fun sendText(s: String) = send(s.toByteArray(), text = true)

    fun close() {
        if (closed) return
        closed = true
        queue.offer(Outgoing(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())))
        Thread {
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            try { socket.close() } catch (_: IOException) {}
        }.start()
    }

    private fun writeLoop() {
        try {
            while (!socket.isClosed) {
                val msg = queue.poll(15, TimeUnit.SECONDS)
                if (msg == null) {
                    writeFrame(OP_PING, ByteArray(0))
                    continue
                }
                writeFrame(msg.opcode, msg.payload)
                if (msg.opcode == OP_CLOSE) break
            }
        } catch (_: IOException) {
        } finally {
            finish()
        }
    }

    private fun readLoop() {
        try {
            val message = java.io.ByteArrayOutputStream()
            var messageOp = -1
            while (!socket.isClosed) {
                val b0 = input.read()
                if (b0 < 0) throw EOFException()
                val b1 = input.read()
                if (b1 < 0) throw EOFException()
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                val masked = b1 and 0x80 != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = (readByte() shl 8 or readByte()).toLong()
                else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or readByte().toLong() }
                }
                if (len > MAX_MESSAGE) throw IOException("frame too large: $len")
                val mask = if (masked) ByteArray(4) { readByte().toByte() } else null
                val payload = ByteArray(len.toInt())
                var off = 0
                while (off < payload.size) {
                    val n = input.read(payload, off, payload.size - off)
                    if (n < 0) throw EOFException()
                    off += n
                }
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

                when (opcode) {
                    OP_PING -> queue.offer(Outgoing(OP_PONG, payload))
                    OP_PONG -> {}
                    OP_CLOSE -> { close(); return }
                    OP_CONTINUATION, OP_TEXT, OP_BINARY -> {
                        if (opcode != OP_CONTINUATION) { message.reset(); messageOp = opcode }
                        message.write(payload)
                        if (fin) {
                            val data = message.toByteArray()
                            message.reset()
                            val l = listener
                            if (messageOp == OP_TEXT) l?.onText(this, String(data, Charsets.UTF_8))
                            else l?.onBinary(this, data)
                        }
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            finish()
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    @Synchronized
    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = java.io.ByteArrayOutputStream(10)
        header.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> header.write(n)
            n < 65536 -> { header.write(126); header.write(n shr 8); header.write(n and 0xFF) }
            else -> {
                header.write(127)
                for (i in 7 downTo 0) header.write(((n.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        output.write(header.toByteArray())
        output.write(payload)
        output.flush()
    }

    private var finished = false
    @Synchronized
    private fun finish() {
        if (finished) return
        finished = true
        closed = true
        try { socket.close() } catch (_: IOException) {}
        // Whatever is still queued has nowhere to go, and anything waiting for room in send() should
        // stop waiting now rather than at the end of its timeout.
        queue.clear()
        listener?.onClose(this)
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_MESSAGE = 1L shl 20
        /**
         * How long [send] waits for room. Long enough that a momentary hiccup keeps its connection,
         * short enough that the encoder thread is never held by one car: at 30fps this is ~8 frames.
         */
        private const val SEND_WAIT_MS = 250L
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun acceptKey(clientKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1").digest((clientKey + GUID).toByteArray(Charsets.ISO_8859_1))
            return Base64.getEncoder().encodeToString(sha1)
        }

        /** Writes the 101 handshake. Caller has already validated [HttpRequest.isWebSocketUpgrade]. */
        fun handshake(request: HttpRequest, output: OutputStream) {
            val key = request.header("sec-websocket-key")!!
            val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n"
            output.write(resp.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
    }
}
