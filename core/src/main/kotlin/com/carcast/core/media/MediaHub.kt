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
    /** Called with the new client count after every attach and detach (the shell server keeps the phone awake while it is > 0). */
    @Volatile var onClientCountChanged: (Int) -> Unit = {}
    /** Called the first time a client's queue is full and a frame is dropped (once per client). */
    @Volatile var onClientStalled: (remote: String, queued: Int) -> Unit = { _, _ -> }

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
            override fun onClose(conn: WebSocketConnection) { if (clients.remove(c)) onClientCountChanged(clients.size) }
        }
        initSegment?.let { conn.send(it) }
        // A late joiner gets the last keyframe immediately so the picture appears without waiting for the GOP.
        lastKey?.let { if (conn.offer(it)) { c.waitingForKey = false; c.sent++ } }
        onClientAttached()
        onClientCountChanged(clients.size)
    }

    open fun onInit(packet: ByteArray) {
        initSegment = packet
        lastKey = null
        for (c in clients) { c.waitingForKey = true; c.conn.send(packet) }
    }

    open fun onFrame(packet: ByteArray, keyframe: Boolean) {
        if (keyframe) lastKey = packet
        for (c in clients) {
            if (c.waitingForKey && !keyframe) continue
            if (c.conn.offer(packet)) {
                c.waitingForKey = false
                c.sent++
            } else {
                c.waitingForKey = true
                if (c.dropped++ == 0L) onClientStalled(c.conn.remote, c.conn.queuedFrames)
            }
        }
    }

    fun closeAll() {
        for (c in clients) c.conn.close()
        clients.clear()
        onClientCountChanged(0)
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
