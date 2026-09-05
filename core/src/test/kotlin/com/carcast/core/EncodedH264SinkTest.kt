package com.carcast.core

import com.carcast.core.media.EncodedH264Sink
import com.carcast.core.media.MediaHub
import com.carcast.mux.AnnexB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Feeds the sink what a MediaCodec encoder would: first the SPS+PPS config buffer, then one
 * Annex-B access unit per frame, taken from the raw test clip. Checks the packets the car would get.
 */
class EncodedH264SinkTest {
    private val clip = listOf("tools/clips/test-720p30.h264", "../tools/clips/test-720p30.h264").map(::File).first { it.exists() }

    private class Capture : MediaHub() {
        val packets = ArrayList<ByteArray>()
        override fun onInit(packet: ByteArray) { packets += packet; super.onInit(packet) }
        override fun onFrame(packet: ByteArray, keyframe: Boolean) { packets += packet; super.onFrame(packet, keyframe) }
    }

    @Test
    fun configThenFramesBecomeInitAndFragments() {
        val nals = AnnexB.splitNalUnits(clip.readBytes())
        val units = AnnexB.groupAccessUnits(nals)
        val sps = nals.first { AnnexB.nalType(it) == AnnexB.NAL_SPS }
        val pps = nals.first { AnnexB.nalType(it) == AnnexB.NAL_PPS }
        val hub = Capture()
        val sink = EncodedH264Sink(hub)
        sink.onCodecConfig(annexB(listOf(sps, pps)))
        assertEquals(1280, sink.width); assertEquals(720, sink.height)

        var pts = 0L
        val frames = units.take(60)
        for (au in frames) { sink.onFrame(annexB(au), pts, AnnexB.isKeyframe(au)); pts += 33_333 }

        assertEquals(1 + frames.size, hub.packets.size)
        assertEquals(MediaHub.TYPE_INIT, hub.packets[0][0])
        assertTrue(String(hub.packets[0], 9, 8, Charsets.ISO_8859_1).contains("ftyp"))
        assertEquals(MediaHub.TYPE_KEY, hub.packets[1][0]) // clip starts with an IDR
        val second = hub.packets[2]
        assertEquals(MediaHub.TYPE_FRAME, second[0])
        assertEquals(33_333L, readPts(second))
        assertTrue(String(second, 9 + 4, 4, Charsets.ISO_8859_1) == "moof")
        assertEquals(frames.size.toLong(), sink.frames)
        assertEquals(frames.count { AnnexB.isKeyframe(it) }.toLong(), sink.keyframes)
    }

    @Test
    fun keyframeWithInlineSpsPpsBootstrapsWithoutConfigBuffer() {
        val nals = AnnexB.splitNalUnits(clip.readBytes())
        val first = AnnexB.groupAccessUnits(nals).first()
        val sps = nals.first { AnnexB.nalType(it) == AnnexB.NAL_SPS }
        val pps = nals.first { AnnexB.nalType(it) == AnnexB.NAL_PPS }
        val hub = Capture()
        val sink = EncodedH264Sink(hub)
        sink.onFrame(annexB(listOf(sps, pps) + first), 0, true)
        assertEquals(2, hub.packets.size)
        assertEquals(MediaHub.TYPE_INIT, hub.packets[0][0])
        assertEquals(MediaHub.TYPE_KEY, hub.packets[1][0])
    }

    private fun annexB(nals: List<ByteArray>): ByteArray =
        nals.fold(ByteArray(0)) { acc, n -> acc + byteArrayOf(0, 0, 0, 1) + n }

    private fun readPts(p: ByteArray): Long { var v = 0L; for (i in 1..8) v = (v shl 8) or (p[i].toLong() and 0xff); return v }
}
