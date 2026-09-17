package com.carcast.core

import com.carcast.core.media.ControlMessage
import com.carcast.core.media.MediaHub
import com.carcast.core.media.VideoSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two control packets the session answers itself, without the injector: PING comes straight back
 * (the car's round-trip probe) and KEYFRAME reaches the video source, at most twice a second.
 */
class ControlChannelTest {
    private object NoAssets : Assets { override fun open(path: String): InputStream? = null }

    /** A source that only counts keyframe requests; the clip is not involved. */
    private class CountingSource : VideoSource {
        val keyframes = AtomicInteger()
        override fun start(hub: MediaHub) {}
        override fun stop() {}
        override fun requestKeyframe() { keyframes.incrementAndGet() }
        override fun info(): Map<String, Any?> = mapOf("source" to "counting")
    }

    /** Just enough of a WebSocket client: handshake, masked binary frames out, unmasked frames in. */
    private class WsClient(port: Int, path: String) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()

        init {
            output.write(("GET $path HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            output.flush()
            var line = readLine()
            assertTrue(line, line.startsWith("HTTP/1.1 101"))
            while (readLine().isNotEmpty()) { /* headers */ }
        }

        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }

        fun sendBinary(payload: ByteArray) {
            val mask = byteArrayOf(1, 2, 3, 4)
            val head = if (payload.size < 126) byteArrayOf((0x80 or 0x2).toByte(), (0x80 or payload.size).toByte())
            else byteArrayOf((0x80 or 0x2).toByte(), (0x80 or 126).toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
            output.write(head); output.write(mask)
            output.write(ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() })
            output.flush()
        }

        /** Next frame as (opcode, payload). */
        fun read(): Pair<Int, ByteArray> {
            val b0 = input.read(); val b1 = input.read()
            var len = b1 and 0x7f
            if (len == 126) len = (input.read() shl 8) or input.read()
            val payload = ByteArray(len)
            var off = 0
            while (off < len) { val n = input.read(payload, off, len - off); check(n > 0); off += n }
            return (b0 and 0x0f) to payload
        }

        override fun close() { socket.close() }
    }

    @Test
    fun pingIsEchoedAndKeyframeRequestsReachTheSourceRateLimited() {
        val port = ServerSocket(0).use { it.localPort }
        val source = CountingSource()
        val session = StreamSession(NoAssets, port, "test", videoSource = source)
        val injected = ArrayList<ControlMessage>()
        session.controlHandler = { injected += it }
        session.start()
        try {
            WsClient(port, "/ws/control").use { ws ->
                val (op0, status) = ws.read() // the status line every control client gets first
                assertEquals(0x1, op0)
                assertTrue(String(status).contains("\"type\":\"status\""))

                val ping = byteArrayOf(6, 0, 0, 0, 9, 0x12, 0x34, 0x56, 0x78)
                ws.sendBinary(ping)
                val (op, echo) = ws.read()
                assertEquals(0x2, op)
                assertArrayEquals(ping, echo)

                ws.sendBinary(byteArrayOf(4))
                ws.sendBinary(byteArrayOf(4)) // inside the 500 ms window: collapsed into the first
                Thread.sleep(300)
                assertEquals(1, source.keyframes.get())
                assertEquals(1L, session.keyframeRequests)
                assertTrue(session.statusJson().contains("\"keyframeRequests\":1"))
                Thread.sleep(400)
                ws.sendBinary(byteArrayOf(4))
                Thread.sleep(300)
                assertEquals(2, source.keyframes.get())

                // A touch still goes to the injector; ping and keyframe never did.
                ws.sendBinary(byteArrayOf(1, 0, 0, 0x7f, 0xff.toByte(), 0x3f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0, 0, 0, 5))
                Thread.sleep(300)
                assertEquals(1, injected.size)
                assertEquals(5L, (injected[0] as ControlMessage.Touch).tMs)
                assertEquals(0L, session.controlErrors)
            }
        } finally {
            session.stop()
        }
    }
}
