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
 * The order of "everything off" is the whole design, and nothing else in the tree would notice if it
 * changed: the hotspot can only be switched by the shell server, so asking the server to die first
 * strands the hotspot with nothing able to turn it off — and the phone keeps broadcasting in the car
 * park while the app reports success. That failure is silent, which is exactly why it is pinned here.
 *
 * A plain socket server on the loopback port stands in for the shell server; the sequence cannot tell
 * the difference, because talking to loopback is all it ever does.
 */
class BulkControlTest {

    private var fake: FakeServer? = null

    @After
    fun tearDown() {
        fake?.close()
        fake = null
    }

    @Test(timeout = 30_000)
    fun offTurnsTheHotspotOffBeforeKillingTheServerThatOwnsIt() {
        val server = FakeServer(hotspotKnown = true, hotspotOn = true).also { fake = it }
        val log = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        server.onRequest = { events.add(it) }

        BulkControl.allOff(log::add) { events.add("session-stopped") }

        // Reads are not steps: what matters is the order of the three things that change state.
        assertEquals(
            "hotspot must go down before the server that is able to switch it, and the session last",
            listOf("POST /api/hotspot", "POST /api/stop", "session-stopped"),
            events.map { it.substringBefore('?') }.filterNot { it.startsWith("GET ") },
        )
        assertTrue(events.toString(), events.any { it.startsWith("POST /api/hotspot") && it.contains("on=0") })
        assertTrue(log.toString(), log.any { it.startsWith("1/3 핫스팟") && it.contains("껐습니다") })
        assertTrue(log.toString(), log.any { it.startsWith("2/3 서버 종료") })
        assertTrue(log.toString(), log.any { it.startsWith("3/3") })
    }

    /** Already off is not a failure and must not cost a pointless radio call — but the rest still runs. */
    @Test(timeout = 30_000)
    fun offSkipsTheHotspotStepWhenItIsAlreadyDown() {
        val server = FakeServer(hotspotKnown = true, hotspotOn = false).also { fake = it }
        val events = CopyOnWriteArrayList<String>()
        val log = CopyOnWriteArrayList<String>()
        server.onRequest = { events.add(it) }

        BulkControl.allOff(log::add) { events.add("session-stopped") }

        assertFalse("nothing to switch, so nothing should have been posted", events.any { it.startsWith("POST /api/hotspot") })
        assertEquals(
            listOf("POST /api/stop", "session-stopped"),
            events.map { it.substringBefore('?') }.filterNot { it.startsWith("GET ") },
        )
        assertTrue(log.toString(), log.any { it.contains("이미 꺼져 있음") })
    }

    /**
     * "Nobody would tell us" is not "it is off". The phone may refuse to report AP state, and in that case
     * the sequence must still try, because the alternative is leaving the hotspot up forever.
     */
    @Test(timeout = 30_000)
    fun unknownHotspotStateIsStillWorthTrying() {
        val server = FakeServer(hotspotKnown = false, hotspotOn = false).also { fake = it }
        val events = CopyOnWriteArrayList<String>()
        val log = CopyOnWriteArrayList<String>()
        server.onRequest = { events.add(it) }

        BulkControl.allOff(log::add) { events.add("session-stopped") }

        assertEquals(
            listOf("POST /api/hotspot", "POST /api/stop", "session-stopped"),
            events.map { it.substringBefore('?') }.filterNot { it.startsWith("GET ") },
        )
        // It only tries blind because something answered at all; with no server it must not (see below).
        assertTrue(events.toString(), events.any { it == "GET /api/status" })
        assertTrue(log.toString(), log.any { it.contains("상태를 알 수 없어") })
    }

    /** No server at all: say so, and never claim the hotspot was dealt with. */
    @Test(timeout = 30_000)
    fun withNoServerItSaysTheHotspotCouldNotBeSwitched() {
        val log = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()

        BulkControl.allOff(log::add) { events.add("session-stopped") }

        assertEquals(listOf("session-stopped"), events)
        assertTrue(log.toString(), log.any { it.contains("서버가 없어 끄지 못했습니다") })
        assertFalse(log.toString(), log.any { it.contains("껐습니다") })
    }

    /**
     * A widget is easy to double-tap, and the second press must not start switching the hotspot back on
     * while the first is still taking it down. Re-entering from inside the sequence is the same claim the
     * two threads would be racing for, without the flakiness of actually racing them.
     */
    @Test(timeout = 30_000)
    fun aSecondSequenceIsRefusedWhileOneIsRunning() {
        val server = FakeServer(hotspotKnown = true, hotspotOn = true).also { fake = it }
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
        assertEquals("only one hotspot switch should have been sent", 1, events.count { it.startsWith("POST /api/hotspot") })
        assertTrue(log.toString(), log.any { it.contains("이미 진행 중") })
    }

    /** And once it is over, the next press is accepted — the claim is released, not leaked. */
    @Test(timeout = 30_000)
    fun theClaimIsReleasedWhenTheSequenceEnds() {
        fake = FakeServer(hotspotKnown = true, hotspotOn = false)
        assertTrue(BulkControl.allOff({}) { })
        assertTrue(BulkControl.allOff({}) { })
    }

    @Test(timeout = 30_000)
    fun readsTheHotspotStateAndDistinguishesUnknownFromOff() {
        fake = FakeServer(hotspotKnown = true, hotspotOn = true)
        assertEquals(true, BulkControl.hotspotOn())
        fake!!.close()

        fake = FakeServer(hotspotKnown = true, hotspotOn = false)
        assertEquals(false, BulkControl.hotspotOn())
        fake!!.close()

        fake = FakeServer(hotspotKnown = false, hotspotOn = false)
        assertEquals(null, BulkControl.hotspotOn())
    }

    /** A server that answers with an error still answers: the reason has to reach the log, not be swallowed. */
    @Test(timeout = 30_000)
    fun aRefusedSwitchReportsTheServersReason() {
        fake = FakeServer(hotspotKnown = true, hotspotOn = false, switchOk = false, switchDetail = "no tethering service")
        val r = BulkControl.setHotspot(true, waitMs = 1_000)
        assertFalse(r.ok)
        assertEquals("no tethering service", r.detail)
    }

    /**
     * The shell server, reduced to what this sequence actually uses. Raw sockets rather than a framework:
     * the app module's unit tests have no Android runtime, and HTTP/1.0 with Connection: close is a dozen lines.
     */
    private class FakeServer(
        private val hotspotKnown: Boolean,
        private val hotspotOn: Boolean,
        private val switchOk: Boolean = true,
        private val switchDetail: String = "started",
    ) : AutoCloseable {
        private val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", Config.HTTP_PORT), 8)
        }
        var onRequest: (String) -> Unit = {}
        @Volatile private var closed = false
        /** Flipped by a successful POST, so a later GET tells the truth the way the real server would. */
        @Volatile private var on = hotspotOn

        private val thread = Thread({
            while (!closed) {
                val client = try { socket.accept() } catch (_: Exception) { return@Thread }
                try { serve(client) } catch (_: Exception) { } finally { runCatching { client.close() } }
            }
        }, "fake-server").apply { isDaemon = true; start() }

        private fun serve(client: Socket) {
            val input = client.getInputStream().bufferedReader()
            val request = input.readLine() ?: return
            val parts = request.split(' ')
            val method = parts[0]
            val target = parts.getOrElse(1) { "/" }
            onRequest("$method $target")
            val path = target.substringBefore('?')
            val body = when {
                path == "/api/status" -> "{\"running\":true}"
                path == "/api/hotspot" && method == "GET" ->
                    "{\"on\":$on,\"known\":$hotspotKnown,\"via\":\"test\",\"controllable\":true}"
                path == "/api/hotspot" -> {
                    if (switchOk) on = target.contains("on=1")
                    "{\"ok\":$switchOk,\"detail\":\"$switchDetail\",\"on\":$on,\"known\":$hotspotKnown,\"via\":\"test\"}"
                }
                path == "/api/stop" -> "{\"ok\":true}"
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
        }
    }
}
