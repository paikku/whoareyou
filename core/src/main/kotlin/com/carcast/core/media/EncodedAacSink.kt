package com.carcast.core.media

import com.carcast.core.Log
import com.carcast.mux.Fmp4AudioWriter

/**
 * Turns what a MediaCodec AAC encoder emits into the fMP4 packets the car's `<audio>` plays: the
 * codec-config buffer (AudioSpecificConfig) becomes the init segment, every AAC frame one
 * moof+mdat fragment. Every packet is a keyframe on the wire — each AAC frame decodes on its own —
 * so the hub forwards audio to a client immediately. Pure JVM, unit-tested with the test tone.
 *
 * Timing: the fragment duration is the nominal 1024 samples; `tfdt` carries the capture timestamp
 * (monotonic clock, the same one the video frames are stamped with), which is what the client
 * uses to keep the two elements in step.
 */
class EncodedAacSink(private val hub: MediaHub) {
    private var writer: Fmp4AudioWriter? = null
    private var config: ByteArray? = null
    @Volatile var frames = 0L
        private set
    @Volatile var lastPtsUs = -1L
        private set
    @Volatile var bytes = 0L
        private set

    /** The BUFFER_FLAG_CODEC_CONFIG buffer (csd-0): the 2+ byte AudioSpecificConfig. */
    @Synchronized
    fun onCodecConfig(asc: ByteArray) {
        if (asc.contentEquals(config) && writer != null) return
        val w = try { Fmp4AudioWriter(asc) } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad AudioSpecificConfig (${asc.size} bytes): ${e.message}"); return
        }
        config = asc
        writer = w
        hub.onInit(MediaHub.packet(MediaHub.TYPE_INIT, 0, w.initSegment()))
        Log.i(TAG, "init segment: ${w.codecString} ${w.sampleRate} Hz ${w.channels}ch")
    }

    /** One raw AAC frame (no ADTS header) as produced by the encoder. */
    @Synchronized
    fun onFrame(aac: ByteArray, ptsUs: Long) {
        val w = writer ?: run { if (frames == 0L) Log.w(TAG, "frame before codec config, dropped"); return }
        if (aac.isEmpty()) return
        hub.onFrame(MediaHub.packet(MediaHub.TYPE_KEY, ptsUs, w.fragment(ptsUs, aac)), keyframe = true)
        frames++
        bytes += aac.size
        lastPtsUs = ptsUs
    }

    val codecString: String get() = writer?.codecString ?: ""
    val sampleRate: Int get() = writer?.sampleRate ?: 0
    val channels: Int get() = writer?.channels ?: 0

    companion object {
        private const val TAG = "EncodedAacSink"
    }
}
