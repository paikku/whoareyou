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
    /** Called when a client was dropped because it could not take the init segment. */
    @Volatile var onClientDropped: (remote: String) -> Unit = {}

    /**
     * A client just fell behind and is now waiting for a keyframe again. Until one comes it sees a frozen
     * picture, and the GOP is two seconds — so the source is asked for an IDR *now* instead. Without this the
     * car pays the full GOP for every hiccup of the hotspot link, which is what "가끔 뚝뚝 끊긴다" is made of.
     * Called on the encoder's output thread: it must not block (MediaCodec.setParameters does not).
     */
    @Volatile var onKeyframeNeeded: () -> Unit = {}

    /** How many clients we let go of because they would not take an init segment. For /api/status. */
    @Volatile var dropped = 0L
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

    open fun onFrame(packet: ByteArray, keyframe: Boolean) {
        if (keyframe) lastKey = packet
        var fellBehind = false
        for (c in clients) {
            if (c.waitingForKey && !keyframe) continue
            if (c.conn.offer(packet)) {
                c.waitingForKey = false
                c.sent++
            } else {
                // Only the *transition* into waiting counts: a client that is already waiting has already
                // asked, and one request per frame would be a keyframe storm on a link that is congested.
                if (!c.waitingForKey) fellBehind = true
                c.waitingForKey = true
                if (c.dropped++ == 0L) onClientStalled(c.conn.remote, c.conn.queuedFrames)
            }
        }
        if (fellBehind) onKeyframeNeeded()
    }

    fun closeAll() {
        for (c in clients) c.conn.close()
        clients.clear()
    }

    companion object {
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
