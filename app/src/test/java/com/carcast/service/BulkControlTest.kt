package com.carcast.service

import com.carcast.Config
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Everything off" stops the server before the session, and nothing else in the tree would notice if that
 * swapped: the kill switch is an HTTP request to the server, and the session is what the app uses to reach
 * it. Do it the other way round and the server is left running with the app no longer able to stop it —
 * which looks, from the outside, exactly like the sequence having worked.
 *
 * A plain socket server on the loopback port stands in for the shell server; the sequence cannot tell the
 * difference, because talking to loopback is all it ever does.
 */
class BulkControlTest {

    private var fake: FakeServer? = null

    @After
    fun tearDown() {
        fake?.close()
        fake = null
    }

    @Test(timeout = 30_000)
    fun offStopsTheServerBeforeTheSessionThatReachesIt() {
        val server = FakeServer().also { fake = it }
        val log = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        server.onRequest = { events.add(it) }

        assertTrue(BulkControl.allOff(log::add) { events.add("session-stopped") })

        assertEquals(
            listOf("POST /api/stop", "session-stopped"),
            events.map { it.substringBefore('?') }.filterNot { it.startsWith("GET ") },
        )
        assertTrue(log.toString(), log.any { it.startsWith("1/2 서버 종료") })
        assertTrue(log.toString(), log.any { it.startsWith("2/2") })
    }

    /** The hotspot is the driver's own switch: the sequence must never touch it. */
    @Test(timeout = 30_000)
    fun offNeverTriesToSwitchTheHotspot() {
        val server = FakeServer().also { fake = it }
        val events = CopyOnWriteArrayList<String>()
        server.onRequest = { events.add(it) }

        BulkControl.allOff({}) { events.add("session-stopped") }

        assertFalse(events.toString(), events.any { it.startsWith("POST /api/hotspot") })
    }

    /** With nothing answering, the session still comes down — that part needs no server at all. */
    @Test(timeout = 30_000)
    fun offWorksWithNoServerAnswering() {
        val log = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()

        assertTrue(BulkControl.allOff(log::add) { events.add("session-stopped") })

        assertEquals(listOf("session-stopped"), events)
        assertTrue(log.toString(), log.any { it.startsWith("2/2") })
    }

    /**
     * A widget is easy to double-tap, and the second press must not start bringing things back up while the
     * first is still taking them down. Re-entering from inside the sequence is the same claim the two threads
     * would be racing for, without the flakiness of actually racing them.
     */
    @Test(timeout = 30_000)
    fun aSecondSequenceIsRefusedWhileOneIsRunning() {
        val server = FakeServer().also { fake = it }
        val events = CopyOnWriteArrayList<String>()
        val log = CopyOnWriteArrayList<String>()
        server.onRequest = { events.add(it) }
        var inner: Boolean? = null

        val outer = BulkControl.allOff(log::add) {
            events.add("session-stopped")
            inner = BulkControl.allOff(log::add) { events.add("SECOND-session-stopped") }
        }

        assertTrue("the first sequence should have run", outer)
        assertEquals("the second must be refused, not queued", false, inner)
        assertFalse(events.toString(), events.contains("SECOND-session-stopped"))
        assertEquals("only one kill switch should have been sent", 1, events.count { it.startsWith("POST /api/stop") })
        assertTrue(log.toString(), log.any { it.contains("이미 진행 중") })
    }

    /** And once it is over, the next press is accepted — the claim is released, not leaked. */
    @Test(timeout = 30_000)
    fun theClaimIsReleasedWhenTheSequenceEnds() {
        fake = FakeServer()
        assertTrue(BulkControl.allOff({}) { })
        assertTrue(BulkControl.allOff({}) { })
    }

    /** Each step is published as it starts, and the phase is back to idle (with the step cleared) when it is over. */
    @Test(timeout = 30_000)
    fun stepsArePublishedAsTheyHappenAndClearedAtTheEnd() {
        fake = FakeServer()
        val seen = CopyOnWriteArrayList<String>()
        BulkControl.onChange = { seen.add("${BulkControl.phase}:${BulkControl.step}") }
        try {
            assertTrue(BulkControl.allOff({}) { })
        } finally {
            BulkControl.onChange = null
        }
        assertEquals(seen.toString(), "TURNING_OFF:", seen.first())
        assertTrue(seen.toString(), seen.contains("TURNING_OFF:1/2 서버 종료 중"))
        assertTrue(seen.toString(), seen.contains("TURNING_OFF:2/2 세션·VPN 종료 중"))
        assertEquals(seen.toString(), "IDLE:", seen.last())
        assertEquals("", BulkControl.step)
    }

    @Test(timeout = 30_000)
    fun serverUpFollowsWhetherAnythingIsAnswering() {
        assertFalse(BulkControl.serverUp())
        fake = FakeServer()
        assertTrue(BulkControl.serverUp())
    }

    /**
     * The shell server, reduced to what this sequence actually uses. Raw sockets rather than a framework:
     * the app module's unit tests have no Android runtime, and HTTP/1.0 with Connection: close is a dozen lines.
     */
    private class FakeServer : AutoCloseable {
        private val socket = bind()
        var onRequest: (String) -> Unit = {}
        @Volatile private var closed = false

        private val thread = Thread({
            while (!closed) {
                val client = try { socket.accept() } catch (_: Exception) { return@Thread }
                try { serve(client) } catch (_: Exception) { } finally { runCatching { client.close() } }
            }
        }, "fake-server").apply { isDaemon = true; start() }

        /**
         * The port is not ours to choose — [BulkControl] only ever talks to the one the phone's server
         * listens on — so tests in the same JVM take turns on it. Closing a listening socket is not always
         * instant from the next bind's point of view, so wait for the port rather than failing the test
         * over the previous test's teardown (this is what broke on CI but never on a developer machine).
         */
        private fun bind(): ServerSocket {
            var last: Exception? = null
            repeat(100) {
                try {
                    return ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("127.0.0.1", Config.HTTP_PORT), 8)
                    }
                } catch (e: java.io.IOException) {
                    last = e
                    Thread.sleep(50)
                }
            }
            throw IllegalStateException("port ${Config.HTTP_PORT} never came free", last)
        }

        private fun serve(client: Socket) {
            val request = client.getInputStream().bufferedReader().readLine() ?: return
            val parts = request.split(' ')
            onRequest("${parts[0]} ${parts.getOrElse(1) { "/" }}")
            val body = when (parts.getOrElse(1) { "" }.substringBefore('?')) {
                "/api/status" -> "{\"running\":true}"
                "/api/stop" -> "{\"ok\":true}"
                else -> "{}"
            }
            write(client.getOutputStream(), body)
        }

        private fun write(out: OutputStream, body: String) {
            val bytes = body.toByteArray()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n"
                    + "Connection: close\r\n\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        }

        override fun close() {
            closed = true
            runCatching { socket.close() }
            thread.interrupt()
            // Wait for the accept loop to actually be gone before the next test tries the same port.
            runCatching { thread.join(2_000) }
        }
    }
}
