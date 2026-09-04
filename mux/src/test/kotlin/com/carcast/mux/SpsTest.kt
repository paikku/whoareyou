package com.carcast.mux

import org.junit.Assert.assertEquals
import org.junit.Test

class SpsTest {
    @Test
    fun parsesClipDimensionsAndProfile() {
        val sps = Sps.parse(Fixtures.sps)
        assertEquals(1280, sps.width)
        assertEquals(720, sps.height)
        assertEquals(66, sps.profileIdc) // baseline
        assertEquals(31, sps.levelIdc)
        assertEquals("avc1.42C01F", sps.codecString) // constraint_set1 is set for baseline by x264
    }

    @Test
    fun unescapeRemovesEmulationPrevention() {
        val escaped = byteArrayOf(0x67, 0, 0, 3, 1, 0, 0, 3, 0, 5)
        val out = Sps.unescape(escaped, 1)
        assertEquals(listOf<Byte>(0, 0, 1, 0, 0, 0, 5), out.toList())
    }
}
