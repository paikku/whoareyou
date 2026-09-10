package com.carcast.mux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Fmp4AudioWriterTest {
    private val aac: File = listOf("../tools/clips/test-tone-48k.aac", "tools/clips/test-tone-48k.aac").map(::File).first { it.exists() }
    private val frames = Adts.parse(aac.readBytes())
    private val asc = Adts.audioSpecificConfig(frames[0].profile, frames[0].sampleRateIndex, frames[0].channels)
    private val writer = Fmp4AudioWriter(asc)

    @Test
    fun adtsFixtureIsAacLc48kStereo() {
        assertTrue("8 s at 21.3 ms per frame", frames.size in 370..380)
        assertEquals(2, frames[0].profile)
        assertEquals(3, frames[0].sampleRateIndex)
        assertEquals(2, frames[0].channels)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), asc)
        assertTrue(frames.all { it.data.size in 4..1500 })
    }

    @Test
    fun configIsDecoded() {
        assertEquals(48000, writer.sampleRate)
        assertEquals(2, writer.channels)
        assertEquals("mp4a.40.2", writer.codecString)
        assertEquals(21_333L, writer.frameDurationUs)
    }

    @Test
    fun initSegmentHasLiveMoovWithEsds() {
        val boxes = parseBoxes(writer.initSegment())
        assertEquals(listOf("ftyp", "moov"), boxes.map { it.type })
        val moov = boxes[1]
        assertEquals(listOf("mvhd", "trak", "mvex"), moov.children().map { it.type })
        assertEquals(0L, moov.child("mvhd").u32(16))
        assertTrue(moov.child("mvex").children().none { it.type == "mehd" })
        assertEquals(Fmp4AudioWriter.AUDIO_TRACK.toLong(), moov.child("mvex").child("trex").u32(4))

        val trak = moov.child("trak")
        assertEquals(Fmp4AudioWriter.AUDIO_TRACK.toLong(), trak.child("tkhd").u32(12))
        val mdia = trak.child("mdia")
        assertEquals("soun", String(mdia.child("hdlr").payload, 8, 4, Charsets.US_ASCII))
        val minf = mdia.child("minf")
        assertEquals(listOf("smhd", "dinf", "stbl"), minf.children().map { it.type })
        val mp4a = minf.child("stbl").child("stsd").child("mp4a", headerSkip = 8)
        assertEquals(2, mp4a.u16(16))                 // channels
        assertEquals(16, mp4a.u16(18))                // sample size
        assertEquals(48000L, mp4a.u32(24) shr 16)     // sample rate 16.16
        val esds = mp4a.child("esds", headerSkip = 28)
        val p = esds.payload
        assertEquals(0x03, p[4].toInt())              // ES_Descriptor after the full-box header
        val dcdAt = 4 + 2 + 3
        assertEquals(0x04, p[dcdAt].toInt())
        assertEquals(0x40, p[dcdAt + 2].toInt())      // objectTypeIndication: MPEG-4 audio
        assertEquals(0x15, p[dcdAt + 3].toInt())      // streamType 5 (audio) << 2 | reserved 1
        val dsiAt = dcdAt + 2 + 13
        assertEquals(0x05, p[dsiAt].toInt())
        assertEquals(2, p[dsiAt + 1].toInt())
        assertArrayEquals(asc, p.copyOfRange(dsiAt + 2, dsiAt + 4))
        assertEquals(0x06, p[dsiAt + 4].toInt())      // SLConfig
        assertEquals(0x02, p[dsiAt + 6].toInt())
    }

    @Test
    fun fragmentHoldsOneSyncSampleWithFrameDuration() {
        val frag = writer.fragment(ptsUs = 5_000_000, sample = frames[10].data)
        val boxes = parseBoxes(frag)
        assertEquals(listOf("moof", "mdat"), boxes.map { it.type })
        val moof = boxes[0]
        assertEquals(frames[10].data.size, boxes[1].size - 8)
        val traf = moof.child("traf")
        assertEquals(Fmp4AudioWriter.AUDIO_TRACK.toLong(), traf.child("tfhd").u32(4))
        assertEquals(5_000_000L, traf.child("tfdt").u64(4))
        val trun = traf.child("trun")
        assertEquals(1L, trun.u32(4))
        assertEquals((moof.size + 8).toLong(), trun.u32(8))
        assertEquals(21_333L, trun.u32(12))
        assertEquals(frames[10].data.size.toLong(), trun.u32(16))
        assertEquals("sync sample", 0x02000000L, trun.u32(20))
        // sequence numbers advance
        val next = parseBoxes(writer.fragment(5_021_333, frames[11].data))[0]
        assertEquals(2L, next.child("mfhd").u32(4))
    }
}
