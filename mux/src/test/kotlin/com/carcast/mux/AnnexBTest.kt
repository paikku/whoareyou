package com.carcast.mux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBTest {
    @Test
    fun splitsThreeAndFourByteStartCodes() {
        val stream = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5)
        val nals = AnnexB.splitNalUnits(stream)
        assertEquals(3, nals.size)
        assertArrayEquals(byteArrayOf(0x67, 1, 2), nals[0])
        assertArrayEquals(byteArrayOf(0x68, 3), nals[1])
        assertArrayEquals(byteArrayOf(0x65, 4, 5), nals[2])
    }

    @Test
    fun groupsClipIntoOneAccessUnitPerFrame() {
        val units = AnnexB.groupAccessUnits(Fixtures.nals).filter { au -> au.any { AnnexB.isVcl(it) } }
        assertEquals("8 s at 30 fps", 240, units.size)
        // GOP 30 -> keyframe every 30 frames, starting at 0.
        units.forEachIndexed { i, au -> assertEquals("frame $i", i % 30 == 0, AnnexB.isKeyframe(au)) }
        // The first unit carries SPS/PPS, which must be stripped from the AVCC sample.
        assertTrue(units[0].any { AnnexB.nalType(it) == AnnexB.NAL_SPS })
        val sample = AnnexB.toAvccSample(units[0])
        var p = 0
        while (p < sample.size) {
            val len = java.nio.ByteBuffer.wrap(sample).getInt(p)
            val type = sample[p + 4].toInt() and 0x1F
            assertFalse("SPS/PPS in sample", type == AnnexB.NAL_SPS || type == AnnexB.NAL_PPS)
            p += 4 + len
        }
        assertEquals(sample.size, p)
    }
}
