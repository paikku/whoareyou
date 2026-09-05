package com.carcast.core

import com.carcast.core.media.ControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlMessageTest {
    @Test
    fun parsesTheWebClientPackets() {
        // encodeTouch(Move, 2, 0.5, 0.25, 1.0) from web/src/protocol.ts
        val touch = byteArrayOf(1, 2, 2, 0x7f.toByte(), 0xff.toByte(), 0x3f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        val t = ControlMessage.parse(touch) as ControlMessage.Touch
        assertEquals(ControlMessage.Touch.MOVE, t.action); assertEquals(2, t.pointerId)
        assertEquals(0.5f, t.x, 0.001f); assertEquals(0.25f, t.y, 0.001f); assertEquals(1f, t.pressure, 0.001f)

        val k = ControlMessage.parse(byteArrayOf(2, 1, 0, 187.toByte())) as ControlMessage.Key
        assertEquals(ControlMessage.Key.UP, k.action); assertEquals(187, k.keycode)

        val text = ControlMessage.parse(byteArrayOf(3) + "안녕 hi".toByteArray()) as ControlMessage.Text
        assertEquals("안녕 hi", text.text)
    }

    @Test
    fun rejectsTruncatedOrUnknown() {
        assertNull(ControlMessage.parse(ByteArray(0)))
        assertNull(ControlMessage.parse(byteArrayOf(1, 0, 0, 0)))
        assertNull(ControlMessage.parse(byteArrayOf(2, 0)))
        assertNull(ControlMessage.parse(byteArrayOf(9, 1, 2)))
    }
}
