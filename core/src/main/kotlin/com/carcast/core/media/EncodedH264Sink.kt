package com.carcast.core.media

import com.carcast.core.Log
import com.carcast.mux.AnnexB
import com.carcast.mux.Fmp4Writer

/**
 * Turns what a MediaCodec H.264 encoder emits (Annex-B access units) into the fMP4 packets the
 * web client plays: the codec-config buffer (SPS+PPS) becomes the init segment, every frame one
 * moof+mdat fragment. Pure JVM so it is unit-tested with the test clip; the Android encoder wrapper
 * only copies buffers into it.
 *
 * Timing: a live fragment's duration is not known until the next frame arrives, so each fragment
 * uses the previous inter-frame gap (first one: [nominalFrameUs]); MSE tolerates that since tfdt
 * carries the real timestamp.
 */
class EncodedH264Sink(private val hub: MediaHub, private val nominalFrameUs: Long = 33_333) {
    private var writer: Fmp4Writer? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastPts = -1L
    private var lastDuration = nominalFrameUs
    @Volatile var frames = 0L
        private set
    @Volatile var keyframes = 0L
        private set

    /**
     * The codec string the SPS really carries ("avc1.<profile><constraints><level>"), not the one the
     * web client declares. The client hardcodes Baseline 3.0 and Chrome accepts it, so only this tells
     * us the encoder's actual profile — which decides whether a JS decoder (Baseline only) is an option.
     */
    @Volatile var codec: String? = null
        private set

    /** The BUFFER_FLAG_CODEC_CONFIG buffer: SPS and PPS in Annex-B. Also accepted inline in a keyframe. */
    @Synchronized
    fun onCodecConfig(annexB: ByteArray) {
        val nals = AnnexB.splitNalUnits(annexB)
        val s = nals.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_SPS }
        val p = nals.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_PPS }
        if (s == null || p == null) { Log.w(TAG, "codec config without SPS/PPS (${nals.size} NALs)"); return }
        if (s.contentEquals(sps) && p.contentEquals(pps) && writer != null) return
        sps = s; pps = p
        val w = Fmp4Writer(s, p)
        writer = w
        codec = w.codecString
        hub.onInit(MediaHub.packet(MediaHub.TYPE_INIT, 0, w.initSegment()))
        Log.i(TAG, "init segment: ${w.width}x${w.height} ${w.codecString}")
    }

    /** One access unit in Annex-B, as produced by the encoder (may include SPS/PPS/AUD; they are stripped). */
    @Synchronized
    fun onFrame(annexB: ByteArray, ptsUs: Long, keyframe: Boolean) {
        val nals = AnnexB.splitNalUnits(annexB)
        if (nals.isEmpty()) return
        if (writer == null || (keyframe && nals.any { AnnexB.nalType(it) == AnnexB.NAL_SPS })) {
            val s = nals.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_SPS }
            val p = nals.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_PPS }
            if (s != null && p != null) onCodecConfig(annexB)
        }
        val w = writer ?: run { Log.w(TAG, "frame before codec config, dropped"); return }
        if (!nals.any { AnnexB.isVcl(it) }) return
        val key = keyframe || AnnexB.isKeyframe(nals)
        if (lastPts >= 0 && ptsUs > lastPts) lastDuration = ptsUs - lastPts
        lastPts = ptsUs
        val fragment = w.fragment(ptsUs, lastDuration, AnnexB.toAvccSample(nals), key)
        hub.onFrame(MediaHub.packet(if (key) MediaHub.TYPE_KEY else MediaHub.TYPE_FRAME, ptsUs, fragment), key)
        frames++
        if (key) keyframes++
    }

    val width: Int get() = writer?.width ?: 0
    val height: Int get() = writer?.height ?: 0

    companion object {
        private const val TAG = "EncodedH264Sink"
    }
}
