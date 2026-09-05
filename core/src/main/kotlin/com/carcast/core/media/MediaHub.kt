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
    }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var initSegment: ByteArray? = null
    @Volatile private var lastKey: ByteArray? = null

    val clientCount: Int get() = clients.size

    /** Called after a client attached (the live source answers with a keyframe request). */
    @Volatile var onClientAttached: () -> Unit = {}

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
        lastKey?.let { if (conn.offer(it)) c.waitingForKey = false }
        onClientAttached()
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
            } else {
                c.waitingForKey = true
            }
        }
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
