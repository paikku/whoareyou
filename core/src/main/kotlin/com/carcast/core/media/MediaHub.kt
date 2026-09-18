package com.carcast.core.media

import com.carcast.core.net.WebSocketConnection
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fans one media stream out to every connected car-side WebSocket.
 * Wire format (see web/src/protocol.ts): [u8 type][u64 pts_us][payload]
 * type 0 = init segment, 1 = delta frame, 2 = keyframe.
 *
 * Backpressure: a client whose send queue is full drops frames until the next keyframe,
 * so it never receives a delta frame whose reference it missed.
 */
open class MediaHub {
    private class Client(val conn: WebSocketConnection) {
        @Volatile var waitingForKey = true
        @Volatile var sent = 0L
        @Volatile var dropped = 0L
    }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var initSegment: ByteArray? = null
    @Volatile private var lastKey: ByteArray? = null

    val clientCount: Int get() = clients.size

    /** Called after a client attached (the live source answers with a keyframe request). */
    @Volatile var onClientAttached: () -> Unit = {}
    /** Called the first time a client's queue is full and a frame is dropped (once per client). */
    @Volatile var onClientStalled: (remote: String, queued: Int) -> Unit = { _, _ -> }
    /**
     * Called when frames were dropped for being stale: the source should emit an IDR so the car can start
     * again straight away instead of waiting out the GOP. The caller rate-limits.
     */
    @Volatile var onNeedKeyframe: () -> Unit = {}
    /** Called when a client was dropped because it could not take the init segment. */
    @Volatile var onClientDropped: (remote: String) -> Unit = {}

    /** How many clients we let go of because they would not take an init segment. For /api/status. */
    @Volatile var dropped = 0L
        private set

    /**
     * How many times the stream was cut for a client that had fallen behind: the frame was dropped for being
     * stale (or the socket would not take it) and everything up to the next keyframe goes with it. One per
     * outage, not per frame — the frames skipped afterwards are waiting for the keyframe that ends it.
     *
     * Unlike [dropped] this is not a fault: it is the hub choosing a fresh picture over a complete one. It
     * climbing during a drive is the link (or the car) not keeping up with the current preset.
     */
    @Volatile var staleDropped = 0L
        private set

    /**
     * What each car-side socket actually got. `sent`/`dropped` count media frames offered since the
     * client attached; `queued` is what is still waiting for the socket to drain. A car that shows
     * one frame and then nothing is either not being sent to (sent stays low) or not reading
     * (dropped and queued climb) — the two look identical from the browser.
     */
    fun clientStats(): List<Map<String, Any?>> = clients.map {
        mapOf("remote" to it.conn.remote, "sent" to it.sent, "dropped" to it.dropped, "queued" to it.conn.queuedFrames, "waitingForKey" to it.waitingForKey)
    }

    fun attach(conn: WebSocketConnection) {
        val c = Client(conn)
        clients += c
        conn.listener = object : WebSocketConnection.Listener {
            override fun onBinary(conn: WebSocketConnection, data: ByteArray) {}
            override fun onText(conn: WebSocketConnection, text: String) {}
            override fun onClose(conn: WebSocketConnection) { clients.remove(c) }
        }
        initSegment?.let { conn.send(it) }
        // A late joiner gets the last keyframe immediately so the picture appears without waiting for the GOP.
        lastKey?.let { if (conn.offer(it)) { c.waitingForKey = false; c.sent++ } }
        onClientAttached()
    }

    /**
     * A new init segment (the encoder restarted — every app start does that). It cannot be dropped:
     * a client without it cannot decode a single frame after it. But it also cannot be *waited* on —
     * this runs on the encoder's output thread, and a car that stopped reading used to park it there
     * for minutes (see WebSocketConnection.send). A socket that will not take the init within the
     * grace period is let go; the car reconnects in a second and attach() gives it the whole picture.
     */
    open fun onInit(packet: ByteArray) {
        initSegment = packet
        lastKey = null
        for (c in clients) {
            c.waitingForKey = true
            if (!c.conn.send(packet)) {
                clients.remove(c)
                dropped++
                onClientDropped(c.conn.remote)
            }
        }
    }

    /**
     * One encoded frame to every client that can still use it.
     *
     * The queue in front of a socket is a latency budget, not a safety net. It used to be the connection's
     * whole capacity (64 frames — over two seconds at 30fps), and nothing was dropped until it was full, so a
     * link that hiccuped did not lose frames: it delivered them all, late. That is the worst outcome of the
     * three. The car sees no backlog of its own (it decodes what arrives as it arrives), its stats read
     * `30fps, dropped 0`, and the only thing wrong is the one thing nobody was measuring — the picture is a
     * second behind the finger. Report #27 (`lag 144ms`, everything else healthy, user still unhappy) is what
     * that looks like from the driver's seat.
     *
     * So P-frames are dropped once [MAX_QUEUED_FRAMES] are already waiting: those frames are stale by the time
     * the socket could send them, and a stale frame is pure latency. Dropping one means the ones after it
     * reference a picture the car does not have, so we stop until the next keyframe — the car's own renderer
     * has used exactly this rule since the backlog threshold went to 8 — and ask the source for one now, or
     * the GOP (10 s) decides when the picture comes back. A keyframe itself is always offered: it is what ends
     * the outage, and the connection's own bound still catches a socket that has truly stopped reading.
     */
    open fun onFrame(packet: ByteArray, keyframe: Boolean) {
        if (keyframe) lastKey = packet
        var stale = false
        for (c in clients) {
            if (c.waitingForKey && !keyframe) continue
            if (!keyframe && c.conn.queuedFrames >= MAX_QUEUED_FRAMES) {
                c.waitingForKey = true
                stale = true
                staleDropped++
                if (c.dropped++ == 0L) onClientStalled(c.conn.remote, c.conn.queuedFrames)
                continue
            }
            if (c.conn.offer(packet)) {
                c.waitingForKey = false
                c.sent++
            } else {
                c.waitingForKey = true
                stale = true
                if (c.dropped++ == 0L) onClientStalled(c.conn.remote, c.conn.queuedFrames)
            }
        }
        if (stale) onNeedKeyframe()
    }

    fun closeAll() {
        for (c in clients) c.conn.close()
        clients.clear()
    }

    companion object {
        /**
         * How many frames may wait in front of one socket before the next one is dropped instead of queued.
         * Four is ~130 ms at 30fps and ~65 ms at 60 — a hiccup's worth of slack, and the ceiling on how stale
         * the picture can get. The connection's own queue (64) stays as the hard backstop for what must not be
         * dropped (the init segment); this is the video policy on top of it.
         */
        const val MAX_QUEUED_FRAMES = 4

        const val TYPE_INIT: Byte = 0
        const val TYPE_FRAME: Byte = 1
        const val TYPE_KEY: Byte = 2

        fun packet(type: Byte, ptsUs: Long, payload: ByteArray): ByteArray {
            val out = ByteArray(9 + payload.size)
            out[0] = type
            for (i in 0 until 8) out[1 + i] = (ptsUs ushr (8 * (7 - i))).toByte()
            System.arraycopy(payload, 0, out, 9, payload.size)
            return out
        }
    }
}
