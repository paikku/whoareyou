package com.carcast.core

import com.carcast.core.media.ControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlMessageTest {
    @Test
    fun parsesTheWebClientPackets() {
        // encodeTouch(Move, 2, 0.5, 0.25, 1.0, 0x01020304) from web/src/protocol.ts
        val touch = byteArrayOf(1, 2, 2, 0x7f.toByte(), 0xff.toByte(), 0x3f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 1, 2, 3, 4)
        val t = ControlMessage.parse(touch) as ControlMessage.Touch
        assertEquals(ControlMessage.Touch.MOVE, t.action); assertEquals(2, t.pointerId)
        assertEquals(0.5f, t.x, 0.001f); assertEquals(0.25f, t.y, 0.001f); assertEquals(1f, t.pressure, 0.001f)
        assertEquals(0x01020304L, t.tMs)

        val k = ControlMessage.parse(byteArrayOf(2, 1, 0, 187.toByte())) as ControlMessage.Key
        assertEquals(ControlMessage.Key.UP, k.action); assertEquals(187, k.keycode)

        val text = ControlMessage.parse(byteArrayOf(3) + "안녕 hi".toByteArray()) as ControlMessage.Text
        assertEquals("안녕 hi", text.text)
    }

    /** The 9-byte touch without a timestamp (tests/device, older builds) still parses; tMs says it was absent. */
    @Test
    fun acceptsTouchWithoutTimestamp() {
        val touch = byteArrayOf(1, 0, 0, 0x7f.toByte(), 0xff.toByte(), 0x3f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        val t = ControlMessage.parse(touch) as ControlMessage.Touch
        assertEquals(ControlMessage.Touch.DOWN, t.action)
        assertEquals(-1L, t.tMs)
    }

    @Test
    fun parsesBatchKeyframeAndPing() {
        // encodeTouchBatch(3, [{0.5,0.25,1,100}, {1,0,0.5,116}])
        val batch = byteArrayOf(
            5, 3, 2,
            0x7f.toByte(), 0xff.toByte(), 0x3f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0, 0, 0, 100,
            0xff.toByte(), 0xff.toByte(), 0, 0, 0x7f.toByte(), 0xff.toByte(), 0, 0, 0, 116,
        )
        val b = ControlMessage.parse(batch) as ControlMessage.TouchBatch
        assertEquals(3, b.pointerId)
        assertEquals(2, b.samples.size)
        assertEquals(0.5f, b.samples[0].x, 0.001f); assertEquals(100L, b.samples[0].tMs)
        assertEquals(1f, b.samples[1].x, 0.001f); assertEquals(0f, b.samples[1].y, 0.001f); assertEquals(116L, b.samples[1].tMs)

        assertTrue(ControlMessage.parse(byteArrayOf(4)) is ControlMessage.Keyframe)

        val p = ControlMessage.parse(byteArrayOf(6, 0, 0, 0, 7, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xfe.toByte())) as ControlMessage.Ping
        assertEquals(7L, p.seq)
        assertEquals(0xfffffffeL, p.tMs) // u32, never sign-extended
    }

    @Test
    fun rejectsTruncatedOrUnknown() {
        assertNull(ControlMessage.parse(ByteArray(0)))
        assertNull(ControlMessage.parse(byteArrayOf(1, 0, 0, 0)))
        assertNull(ControlMessage.parse(byteArrayOf(2, 0)))
        assertNull(ControlMessage.parse(byteArrayOf(9, 1, 2)))
        assertNull(ControlMessage.parse(byteArrayOf(5, 0, 0)))          // empty batch
        assertNull(ControlMessage.parse(byteArrayOf(5, 0, 2, 1, 2, 3))) // batch shorter than it claims
        assertNull(ControlMessage.parse(byteArrayOf(6, 1, 2)))
    }
}
