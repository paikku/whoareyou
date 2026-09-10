package com.carcast.core

import com.carcast.core.media.EncodedAacSink
import com.carcast.core.media.MediaHub
import com.carcast.mux.Adts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Feeds the sink what a MediaCodec AAC encoder would: the AudioSpecificConfig, then one raw AAC
 * frame per 1024 samples, taken from the test tone. Checks the packets the car's `<audio>` gets.
 */
class EncodedAacSinkTest {
    private val aac = listOf("tools/clips/test-tone-48k.aac", "../tools/clips/test-tone-48k.aac").map(::File).first { it.exists() }

    private class Capture : MediaHub(replayLastKey = false) {
        val packets = ArrayList<ByteArray>()
        override fun onInit(packet: ByteArray) { packets += packet; super.onInit(packet) }
        override fun onFrame(packet: ByteArray, keyframe: Boolean) { packets += packet; super.onFrame(packet, keyframe) }
    }

    @Test
    fun configThenFramesBecomeInitAndFragments() {
        val frames = Adts.parse(aac.readBytes()).take(50)
        val hub = Capture()
        val sink = EncodedAacSink(hub)
        sink.onCodecConfig(Adts.audioSpecificConfig(frames[0].profile, frames[0].sampleRateIndex, frames[0].channels))
        assertEquals("mp4a.40.2", sink.codecString)
        assertEquals(48000, sink.sampleRate)
        assertEquals(2, sink.channels)

        var pts = 1_000_000L
        for (f in frames) { sink.onFrame(f.data, pts); pts += 21_333 }

        assertEquals(1 + frames.size, hub.packets.size)
        assertEquals(MediaHub.TYPE_INIT, hub.packets[0][0])
        assertTrue(String(hub.packets[0], 9, 8, Charsets.ISO_8859_1).contains("ftyp"))
        for (i in 1..frames.size) assertEquals("every AAC frame is a keyframe", MediaHub.TYPE_KEY, hub.packets[i][0])
        assertEquals(1_000_000L, readPts(hub.packets[1]))
        assertEquals(1_021_333L, readPts(hub.packets[2]))
        assertEquals("moof", String(hub.packets[2], 9 + 4, 4, Charsets.ISO_8859_1))
        assertEquals(frames.size.toLong(), sink.frames)
        assertEquals(pts - 21_333, sink.lastPtsUs)
    }

    @Test
    fun framesBeforeConfigAreDroppedAndConfigIsIdempotent() {
        val frames = Adts.parse(aac.readBytes()).take(3)
        val hub = Capture()
        val sink = EncodedAacSink(hub)
        sink.onFrame(frames[0].data, 0)
        assertEquals(0, hub.packets.size)
        val asc = byteArrayOf(0x11, 0x90.toByte())
        sink.onCodecConfig(asc)
        sink.onCodecConfig(asc.copyOf())
        assertEquals("same config again does not restart the stream", 1, hub.packets.size)
        sink.onCodecConfig(byteArrayOf(0x12, 0x10)) // 44.1 kHz stereo: a real change → new init segment
        assertEquals(2, hub.packets.size)
        assertEquals(44100, sink.sampleRate)
    }

    private fun readPts(p: ByteArray): Long { var v = 0L; for (i in 1..8) v = (v shl 8) or (p[i].toLong() and 0xff); return v }
}
