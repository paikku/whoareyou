package com.carcast.mux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fmp4WriterTest {
    private val writer = Fmp4Writer(Fixtures.sps, Fixtures.pps)

    @Test
    fun initSegmentHasLiveMoovWithAvcC() {
        val boxes = parseBoxes(writer.initSegment())
        assertEquals(listOf("ftyp", "moov"), boxes.map { it.type })
        val moov = boxes[1]
        assertEquals(listOf("mvhd", "trak", "mvex"), moov.children().map { it.type })

        val mvhd = moov.child("mvhd")
        assertEquals(Fmp4Writer.TIMESCALE.toLong(), mvhd.u32(12))
        assertEquals("duration must be 0 for live", 0L, mvhd.u32(16))
        assertTrue("no mehd (unknown duration)", moov.child("mvex").children().none { it.type == "mehd" })

        val tkhd = moov.child("trak").child("tkhd")
        assertEquals(1280L, tkhd.u32(76) shr 16)
        assertEquals(720L, tkhd.u32(80) shr 16)

        val stsd = moov.child("trak").child("mdia").child("minf").child("stbl").child("stsd")
        val avc1 = stsd.child("avc1", headerSkip = 8)
        assertEquals(1280, avc1.u16(24))
        assertEquals(720, avc1.u16(26))
        val avcC = avc1.child("avcC", headerSkip = 78)
        val p = avcC.payload
        assertEquals(1, p[0].toInt())
        assertEquals(66, p[1].toInt())
        assertEquals(0xFF, p[4].toInt() and 0xFF)
        val spsLen = ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
        assertEquals(Fixtures.sps.size, spsLen)
    }

    @Test
    fun fragmentDataOffsetPointsAtMdatPayload() {
        val units = AnnexB.groupAccessUnits(Fixtures.nals).filter { au -> au.any { AnnexB.isVcl(it) } }
        val sample = AnnexB.toAvccSample(units[0])
        val frag = writer.fragment(ptsUs = 0, durationUs = 33_333, sample = sample, keyframe = true)
        val boxes = parseBoxes(frag)
        assertEquals(listOf("moof", "mdat"), boxes.map { it.type })
        val moof = boxes[0]
        val mdat = boxes[1]
        assertEquals(sample.size, mdat.size - 8)

        val traf = moof.child("traf")
        val tfhd = traf.child("tfhd")
        assertEquals("default-base-is-moof", 0x020000L, tfhd.u32(0) and 0xFFFFFFL)
        val tfdt = traf.child("tfdt")
        assertEquals(1, tfdt.payload[0].toInt())
        assertEquals(0L, tfdt.u64(4))
        val trun = traf.child("trun")
        assertEquals(1L, trun.u32(4))
        assertEquals("data_offset = moof size + mdat header", (moof.size + 8).toLong(), trun.u32(8))
        assertEquals(33_333L, trun.u32(12))
        assertEquals(sample.size.toLong(), trun.u32(16))
        assertEquals("sync sample flags", 0x02000000L, trun.u32(20))

        val delta = writer.fragment(33_333, 33_333, AnnexB.toAvccSample(units[1]), keyframe = false)
        val trun2 = parseBoxes(delta)[0].child("traf").child("trun")
        assertEquals("non-sync sample flags", 0x01010000L, trun2.u32(20))
        assertEquals("sequence increments", 2L, parseBoxes(delta)[0].child("mfhd").u32(4))
    }
}
