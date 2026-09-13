package com.carcast.core

import com.carcast.core.media.MediaHub
import com.carcast.core.net.WebSocketConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A car that stops reading must never stop the phone.
 *
 * Real-car reports #41-42: the picture froze (0fps, "폰 무응답 16s") while /api/status kept answering
 * and both sockets reconnected in a loop. The cause was here: the init segment went out with a
 * *blocking* enqueue, so one socket whose TCP window had closed parked the encoder's output thread —
 * which holds EncodedH264Sink's monitor — until TCP gave up, minutes later. Every app start emits an
 * init segment, so the trigger was ordinary use.
 *
 * These tests pin the contract that replaced it: a send that cannot be delivered gives up and lets the
 * connection go. Nothing here needs a phone — a socket pair with no reader is the same condition.
 */
class StalledClientTest {
    /** A connection whose writer never runs: everything offered stays in the queue, as for a car that stopped reading. */
    private class Stalled : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val peer = Socket("127.0.0.1", listener.localPort)
        private val accepted = listener.accept()
        val conn = WebSocketConnection(
            accepted,
            BufferedInputStream(accepted.getInputStream()),
            accepted.getOutputStream(),
            queueCapacity = 2,
        )

        /** Fill the queue. No writer thread was started, so nothing drains it. */
        fun fill() {
            assertTrue(conn.offer(byteArrayOf(1)))
            assertTrue(conn.offer(byteArrayOf(2)))
            assertFalse("queue should be full now", conn.offer(byteArrayOf(3)))
        }

        override fun close() {
            runCatching { accepted.close() }; runCatching { peer.close() }; runCatching { listener.close() }
        }
    }

    @Test(timeout = 10_000)
    fun sendGivesUpInsteadOfBlockingForever() {
        Stalled().use { s ->
            s.fill()
            val startedAt = System.currentTimeMillis()
            val queued = s.conn.send(byteArrayOf(9))
            val waited = System.currentTimeMillis() - startedAt
            assertFalse("a full queue must not swallow the send", queued)
            assertTrue("send parked the caller for ${waited}ms", waited < 3_000)
            assertFalse("the connection should be closed, not left half-alive", s.conn.offer(byteArrayOf(10)))
        }
    }

    @Test(timeout = 10_000)
    fun aClientThatWillNotTakeTheInitSegmentIsLetGo() {
        Stalled().use { s ->
            val hub = MediaHub()
            var droppedRemote: String? = null
            hub.onClientDropped = { droppedRemote = it }
            hub.attach(s.conn)
            assertEquals(1, hub.clientCount)
            s.fill()

            val startedAt = System.currentTimeMillis()
            hub.onInit(MediaHub.packet(MediaHub.TYPE_INIT, 0, ByteArray(16)))
            val waited = System.currentTimeMillis() - startedAt

            assertTrue("the encoder thread was held for ${waited}ms by one car", waited < 3_000)
            assertEquals("the stalled client should be gone", 0, hub.clientCount)
            assertEquals(1L, hub.dropped)
            assertTrue("it should say which client", droppedRemote != null)
        }
    }

    /** A healthy client keeps its connection: the grace period is for hiccups, not a hair trigger. */
    @Test(timeout = 10_000)
    fun aClientThatIsReadingKeepsTheInitSegment() {
        Stalled().use { s ->
            val hub = MediaHub()
            hub.attach(s.conn)
            assertTrue(hub.onInitAcceptedBy(s.conn))
            assertEquals(1, hub.clientCount)
            assertEquals(0L, hub.dropped)
        }
    }

    private fun MediaHub.onInitAcceptedBy(conn: WebSocketConnection): Boolean {
        onInit(MediaHub.packet(MediaHub.TYPE_INIT, 0, ByteArray(16)))
        return conn.queuedFrames > 0
    }
}
