package com.carcast.core

import com.carcast.core.media.MediaHub
import com.carcast.core.net.WebSocketConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A frame that waits its turn is latency, not resilience.
 *
 * The queue in front of a car's video socket used to be the connection's whole capacity — 64 frames, over two
 * seconds at 30fps — and nothing was dropped until it was full. So a link that stumbled did not lose frames,
 * it delivered them all, late, and the car had no way to tell: it decodes what arrives as it arrives, so its
 * own backlog stays at zero and its stats read "30fps, dropped 0" while the picture is a second behind the
 * finger. Report #27 is that shape (`lag 144ms`, everything healthy, the driver still unhappy).
 *
 * The contract pinned here: at most [MediaHub.MAX_QUEUED_FRAMES] frames wait in front of a socket; past that
 * P-frames are dropped until the next keyframe and the source is asked for one now, because the GOP is ten
 * seconds. A keyframe is always offered — it is what ends the outage.
 */
class StaleFrameTest {
    /** A connection with no writer thread: whatever is offered stays queued, like a car that stopped reading. */
    private class Backed(capacity: Int = 64) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val peer = Socket("127.0.0.1", listener.localPort)
        private val accepted = listener.accept()
        val conn = WebSocketConnection(
            accepted,
            BufferedInputStream(accepted.getInputStream()),
            accepted.getOutputStream(),
            queueCapacity = capacity,
        )

        override fun close() {
            runCatching { accepted.close() }; runCatching { peer.close() }; runCatching { listener.close() }
        }
    }

    private fun frame(n: Int) = MediaHub.packet(MediaHub.TYPE_FRAME, n * 33_333L, ByteArray(64))
    private fun key(n: Int) = MediaHub.packet(MediaHub.TYPE_KEY, n * 33_333L, ByteArray(64))

    @Test(timeout = 10_000)
    fun framesStopQueuingLongBeforeTheSocketIsFull() {
        Backed().use { b ->
            val hub = MediaHub()
            var asks = 0
            hub.onNeedKeyframe = { asks++ }
            hub.attach(b.conn)

            hub.onFrame(key(0), true) // the client is waiting for a key; this is the one it wanted
            for (i in 1 until MediaHub.MAX_QUEUED_FRAMES) hub.onFrame(frame(i), false)
            assertEquals("the budget should be filled, not exceeded", MediaHub.MAX_QUEUED_FRAMES, b.conn.queuedFrames)
            assertEquals("nothing is late yet, so nothing to ask for", 0, asks)

            // Everything from here is stale before it could be sent. The first one cuts the stream; the rest
            // are already waiting for the keyframe that cut buys, which is why the cut is counted once.
            for (i in 0 until 50) hub.onFrame(frame(MediaHub.MAX_QUEUED_FRAMES + i), false)
            assertEquals("stale frames were queued anyway", MediaHub.MAX_QUEUED_FRAMES, b.conn.queuedFrames)
            assertEquals("one outage, one cut", 1L, hub.staleDropped)
            assertEquals("the source was never asked for a way out", 1, asks)

            // The keyframe arrives, the car can start again — and falls behind again, because nothing drained.
            hub.onFrame(key(99), true)
            for (i in 0 until 10) hub.onFrame(frame(100 + i), false)
            assertEquals("the second outage was not seen", 2L, hub.staleDropped)
            assertEquals(2, asks)
        }
    }

    @Test(timeout = 10_000)
    fun aKeyframeIsAlwaysOfferedBecauseItIsWhatEndsTheOutage() {
        Backed().use { b ->
            val hub = MediaHub()
            hub.attach(b.conn)
            hub.onFrame(key(0), true)
            for (i in 1..20) hub.onFrame(frame(i), false)
            val queuedWhileStalled = b.conn.queuedFrames

            hub.onFrame(key(21), true)
            assertEquals("the keyframe was dropped with the stale P-frames", queuedWhileStalled + 1, b.conn.queuedFrames)

            // And it resumes the stream: the frames after it reference a picture the car now has.
            hub.onFrame(frame(22), false)
            assertEquals(queuedWhileStalled + 1, b.conn.queuedFrames) // still over budget, so still dropping
            assertTrue("the client should not have been disconnected", hub.clientCount == 1)
        }
    }

    /** A car that reads keeps getting everything: the budget is a ceiling on staleness, not a throttle. */
    @Test(timeout = 10_000)
    fun aClientThatKeepsUpLosesNothing() {
        Backed().use { b ->
            val hub = MediaHub()
            hub.attach(b.conn)
            // The writer thread drains the queue into the socket, and these frames are small enough that the
            // peer's receive buffer takes them without anyone reading — which is the point: the queue empties.
            b.conn.start()

            hub.onFrame(key(0), true)
            for (i in 1..30) {
                hub.onFrame(frame(i), false)
                Thread.sleep(5) // slower than the socket, which is what a car that keeps up looks like
            }
            assertEquals("a reading client must not lose frames", 0L, hub.staleDropped)
        }
    }
}
