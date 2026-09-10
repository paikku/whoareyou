package com.carcast.mux

/**
 * ADTS (the raw .aac container ffmpeg writes) → AAC frames + the AudioSpecificConfig MSE needs.
 * Only used by the test-clip generator and unit tests: on the phone the MediaCodec AAC encoder
 * hands over raw frames and the config buffer directly.
 */
object Adts {
    class Frame(val data: ByteArray, val profile: Int, val sampleRateIndex: Int, val channels: Int)

    val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun parse(data: ByteArray): List<Frame> {
        val out = ArrayList<Frame>()
        var p = 0
        while (p + 7 <= data.size) {
            val b1 = data[p].toInt() and 0xFF
            val b2 = data[p + 1].toInt() and 0xFF
            require(b1 == 0xFF && (b2 and 0xF0) == 0xF0) { "no ADTS sync word at $p" }
            val protectionAbsent = (b2 and 1) == 1
            val b3 = data[p + 2].toInt() and 0xFF
            val b4 = data[p + 3].toInt() and 0xFF
            val b5 = data[p + 4].toInt() and 0xFF
            val b6 = data[p + 5].toInt() and 0xFF
            val profile = (b3 ushr 6) + 1 // 1-based audio object type: 2 = AAC-LC
            val srIndex = (b3 ushr 2) and 0xF
            val channels = ((b3 and 1) shl 2) or (b4 ushr 6)
            val frameLength = ((b4 and 3) shl 11) or (b5 shl 3) or (b6 ushr 5)
            val headerLength = if (protectionAbsent) 7 else 9
            require(frameLength >= headerLength && p + frameLength <= data.size) { "bad ADTS frame length $frameLength at $p" }
            out += Frame(data.copyOfRange(p + headerLength, p + frameLength), profile, srIndex, channels)
            p += frameLength
        }
        return out
    }

    /** The 2-byte AudioSpecificConfig (ISO 14496-3 §1.6.2.1) for AAC-LC and friends: [aot:5][srIndex:4][channels:4][pad:3]. */
    fun audioSpecificConfig(profile: Int, sampleRateIndex: Int, channels: Int): ByteArray {
        val v = (profile shl 11) or (sampleRateIndex shl 7) or (channels shl 3)
        return byteArrayOf((v ushr 8).toByte(), v.toByte())
    }
}
