package com.carcast.core.media

/**
 * Car → phone control packets (web/src/protocol.ts):
 *   kind 1 TOUCH       [u8 action][u8 pointerId][u16 x][u16 y][u16 pressure][u32 tMs]   x,y,pressure normalised 0..65535
 *                      tMs is the car's clock in ms (wraps at 2^32); a 9-byte packet without it is accepted (tMs = -1).
 *   kind 2 KEY         [u8 action][u16 keycode]
 *   kind 3 TEXT        [utf-8]
 *   kind 4 KEYFRAME    (no body) — the car wants an IDR now
 *   kind 5 TOUCH_BATCH [u8 pointerId][u8 n] then n × [u16 x][u16 y][u16 pressure][u32 tMs] — MOVE samples, oldest first
 *   kind 6 PING        [u32 seq][u32 tMs] — echoed back verbatim by the session (StreamSession), never injected
 * Pure JVM so the parsing is unit-tested; the shell process turns these into Android input events.
 */
sealed class ControlMessage {
    /** [tMs] is the car's clock for this sample, or -1 when the packet did not carry one. */
    data class Touch(val action: Int, val pointerId: Int, val x: Float, val y: Float, val pressure: Float, val tMs: Long = -1) : ControlMessage() {
        companion object { const val DOWN = 0; const val UP = 1; const val MOVE = 2; const val CANCEL = 3 }
    }
    data class Sample(val x: Float, val y: Float, val pressure: Float, val tMs: Long)
    /** A run of MOVE samples for one finger that the car's browser coalesced into one frame; oldest first. */
    data class TouchBatch(val pointerId: Int, val samples: List<Sample>) : ControlMessage()
    data class Key(val action: Int, val keycode: Int) : ControlMessage() {
        companion object { const val DOWN = 0; const val UP = 1 }
    }
    data class Text(val text: String) : ControlMessage()
    object Keyframe : ControlMessage()
    data class Ping(val seq: Long, val tMs: Long) : ControlMessage()

    companion object {
        const val KIND_TOUCH = 1
        const val KIND_KEY = 2
        const val KIND_TEXT = 3
        const val KIND_KEYFRAME = 4
        const val KIND_TOUCH_BATCH = 5
        const val KIND_PING = 6

        /** Null for an unknown kind or a truncated packet. */
        fun parse(data: ByteArray): ControlMessage? {
            if (data.isEmpty()) return null
            fun u16(i: Int) = ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
            return when (data[0].toInt()) {
                KIND_TOUCH -> if (data.size < 9) null else Touch(
                    action = data[1].toInt() and 0xff,
                    pointerId = data[2].toInt() and 0xff,
                    x = u16(3) / 65535f, y = u16(5) / 65535f, pressure = u16(7) / 65535f,
                    tMs = if (data.size >= 13) u32(9) else -1,
                )
                KIND_KEY -> if (data.size < 4) null else Key(data[1].toInt() and 0xff, u16(2))
                KIND_TEXT -> Text(String(data, 1, data.size - 1, Charsets.UTF_8))
                KIND_KEYFRAME -> Keyframe
                KIND_TOUCH_BATCH -> {
                    if (data.size < 3) return null
                    val n = data[2].toInt() and 0xff
                    if (n == 0 || data.size < 3 + 10 * n) return null
                    TouchBatch(
                        pointerId = data[1].toInt() and 0xff,
                        samples = List(n) { i ->
                            val o = 3 + 10 * i
                            Sample(u16(o) / 65535f, u16(o + 2) / 65535f, u16(o + 4) / 65535f, u32(o + 6))
                        },
                    )
                }
                KIND_PING -> if (data.size < 9) null else Ping(u32(1), u32(5))
                else -> null
            }
        }
    }
}
