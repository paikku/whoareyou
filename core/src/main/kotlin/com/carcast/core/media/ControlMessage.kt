package com.carcast.core.media

/**
 * Car → phone control packets (web/src/protocol.ts):
 *   kind 1 TOUCH [u8 action][u8 pointerId][u16 x][u16 y][u16 pressure]  x,y,pressure normalised 0..65535
 *   kind 2 KEY   [u8 action][u16 keycode]
 *   kind 3 TEXT  [utf-8]
 * Pure JVM so the parsing is unit-tested; the shell process turns these into Android input events.
 */
sealed class ControlMessage {
    data class Touch(val action: Int, val pointerId: Int, val x: Float, val y: Float, val pressure: Float) : ControlMessage() {
        companion object { const val DOWN = 0; const val UP = 1; const val MOVE = 2; const val CANCEL = 3 }
    }
    data class Key(val action: Int, val keycode: Int) : ControlMessage() {
        companion object { const val DOWN = 0; const val UP = 1 }
    }
    data class Text(val text: String) : ControlMessage()

    companion object {
        const val KIND_TOUCH = 1
        const val KIND_KEY = 2
        const val KIND_TEXT = 3

        /** Null for an unknown kind or a truncated packet. */
        fun parse(data: ByteArray): ControlMessage? {
            if (data.isEmpty()) return null
            fun u16(i: Int) = ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            return when (data[0].toInt()) {
                KIND_TOUCH -> if (data.size < 9) null else Touch(
                    action = data[1].toInt() and 0xff,
                    pointerId = data[2].toInt() and 0xff,
                    x = u16(3) / 65535f, y = u16(5) / 65535f, pressure = u16(7) / 65535f,
                )
                KIND_KEY -> if (data.size < 4) null else Key(data[1].toInt() and 0xff, u16(2))
                KIND_TEXT -> Text(String(data, 1, data.size - 1, Charsets.UTF_8))
                else -> null
            }
        }
    }
}
