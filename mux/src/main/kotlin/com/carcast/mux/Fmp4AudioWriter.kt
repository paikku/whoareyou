package com.carcast.mux

/**
 * Fragmented MP4 writer for the AAC-LC track, mirroring [Fmp4Writer]: live (duration 0, no `mehd`),
 * one AAC frame per `moof`, microsecond timescale so `tfdt` is the wire pts unchanged.
 *
 * The audio goes to the car on its own WebSocket and its own MediaSource (an `<audio>` element
 * next to the `<video>`), so a hiccup on either side never stalls the other; the two are kept in
 * step by the client from the shared phone clock. That is why this is a separate single-track
 * writer rather than a second track in [Fmp4Writer].
 */
class Fmp4AudioWriter(
    /** AudioSpecificConfig as the encoder's codec-config buffer (csd-0) delivers it, e.g. 11 90 for AAC-LC 48 kHz stereo. */
    private val audioSpecificConfig: ByteArray,
) {
    private var sequence = 0

    val objectType: Int = (audioSpecificConfig[0].toInt() and 0xFF) ushr 3
    val sampleRate: Int
    val channels: Int

    init {
        require(audioSpecificConfig.size >= 2) { "AudioSpecificConfig too short" }
        val v = ((audioSpecificConfig[0].toInt() and 0xFF) shl 8) or (audioSpecificConfig[1].toInt() and 0xFF)
        val srIndex = (v ushr 7) and 0xF
        require(srIndex < Adts.SAMPLE_RATES.size) { "explicit sample rate in AudioSpecificConfig not supported" }
        sampleRate = Adts.SAMPLE_RATES[srIndex]
        channels = (v ushr 3) and 0xF
    }

    /** RFC 6381 codec string, e.g. mp4a.40.2 for AAC-LC. */
    val codecString: String get() = "mp4a.40.$objectType"

    /** Duration of one 1024-sample AAC frame in microseconds (21333 at 48 kHz). */
    val frameDurationUs: Long get() = 1024L * TIMESCALE / sampleRate

    fun initSegment(): ByteArray {
        val ftyp = Box.of("ftyp").ascii("isom").u32(0x200).ascii("isom").ascii("iso5").ascii("mp41").build()
        val moov = Box.of("moov")
            .child(mvhd())
            .child(trak())
            .child(Box.of("mvex").child(trex()).build())
            .build()
        return ftyp + moov
    }

    /** One media segment (`moof` + `mdat`) holding exactly one raw AAC frame (no ADTS header). */
    fun fragment(ptsUs: Long, sample: ByteArray, durationUs: Long = frameDurationUs): ByteArray {
        sequence++
        val moofWithoutOffset = moof(ptsUs, durationUs, sample.size, dataOffset = 0)
        val moof = moof(ptsUs, durationUs, sample.size, dataOffset = moofWithoutOffset.size + 8)
        val mdat = Box.of("mdat").bytes(sample).build()
        return moof + mdat
    }

    private fun moof(ptsUs: Long, durationUs: Long, sampleSize: Int, dataOffset: Int): ByteArray {
        val mfhd = Box.of("mfhd").fullHeader(0, 0).u32(sequence).build()
        val tfhd = Box.of("tfhd").fullHeader(0, 0x020000).u32(AUDIO_TRACK).build() // default-base-is-moof
        val tfdt = Box.of("tfdt").fullHeader(1, 0).u64(ptsUs).build()
        val trun = Box.of("trun")
            .fullHeader(0, 0x000001 or 0x000100 or 0x000200 or 0x000400) // data-offset, duration, size, flags present
            .u32(1)
            .u32(dataOffset)
            .u32(durationUs)
            .u32(sampleSize)
            .u32(0x02000000) // every AAC frame is a sync sample
            .build()
        val traf = Box.of("traf").child(tfhd).child(tfdt).child(trun).build()
        return Box.of("moof").child(mfhd).child(traf).build()
    }

    private fun mvhd(): ByteArray = Box.of("mvhd")
        .fullHeader(0, 0)
        .u32(0).u32(0)
        .u32(TIMESCALE)
        .u32(0)                   // duration: unknown (live)
        .u32(0x00010000)          // rate 1.0
        .u16(0x0100)              // volume 1.0
        .zeros(10)
        .bytes(UNITY_MATRIX)
        .zeros(24)
        .u32(AUDIO_TRACK + 1)
        .build()

    private fun trak(): ByteArray = Box.of("trak").child(tkhd()).child(mdia()).build()

    private fun tkhd(): ByteArray = Box.of("tkhd")
        .fullHeader(0, 0x000007)
        .u32(0).u32(0)
        .u32(AUDIO_TRACK)
        .u32(0)
        .u32(0)                   // duration
        .zeros(8)
        .u16(0).u16(0)            // layer, alternate_group
        .u16(0x0100).u16(0)       // volume 1.0 (audio), reserved
        .bytes(UNITY_MATRIX)
        .u32(0).u32(0)            // width, height: none for audio
        .build()

    private fun mdia(): ByteArray {
        val mdhd = Box.of("mdhd")
            .fullHeader(0, 0)
            .u32(0).u32(0)
            .u32(TIMESCALE)
            .u32(0)
            .u16(0x55C4)          // language: und
            .u16(0)
            .build()
        val hdlr = Box.of("hdlr")
            .fullHeader(0, 0)
            .u32(0)
            .ascii("soun")
            .zeros(12)
            .ascii("SoundHandler").u8(0)
            .build()
        return Box.of("mdia").child(mdhd).child(hdlr).child(minf()).build()
    }

    private fun minf(): ByteArray {
        val smhd = Box.of("smhd").fullHeader(0, 0).u16(0).u16(0).build() // balance 0
        val dref = Box.of("dref").fullHeader(0, 0).u32(1).child(Box.of("url ").fullHeader(0, 1).build()).build()
        val dinf = Box.of("dinf").child(dref).build()
        return Box.of("minf").child(smhd).child(dinf).child(stbl()).build()
    }

    private fun stbl(): ByteArray {
        val stsd = Box.of("stsd").fullHeader(0, 0).u32(1).child(mp4a()).build()
        val empty = { name: String -> Box.of(name).fullHeader(0, 0).u32(0).build() }
        return Box.of("stbl")
            .child(stsd)
            .child(empty("stts"))
            .child(empty("stsc"))
            .child(Box.of("stsz").fullHeader(0, 0).u32(0).u32(0).build())
            .child(empty("stco"))
            .build()
    }

    private fun mp4a(): ByteArray = Box.of("mp4a")
        .zeros(6).u16(1)          // reserved, data_reference_index
        .zeros(8)                 // version, revision, vendor
        .u16(channels)
        .u16(16)                  // sample size
        .u16(0).u16(0)            // pre_defined, reserved
        .u32(sampleRate shl 16)   // 16.16 fixed point
        .child(esds())
        .build()

    /**
     * MPEG-4 ES_Descriptor (14496-1 §7.2.6.5): ES → DecoderConfig(objectTypeIndication 0x40 = MPEG-4
     * audio, streamType 5 = audio) → DecoderSpecificInfo(AudioSpecificConfig) → SLConfig(2 = MP4).
     */
    private fun esds(): ByteArray {
        val asc = audioSpecificConfig
        val dsi = descriptor(0x05, asc)
        val dcd = descriptor(
            0x04,
            byteArrayOf(0x40, (0x05 shl 2 or 1).toByte()) +
                u24(0) +                       // bufferSizeDB
                u32(0) + u32(0) +              // maxBitrate, avgBitrate: unknown
                dsi,
        )
        val sl = descriptor(0x06, byteArrayOf(0x02))
        val es = descriptor(0x03, byteArrayOf(0, 0, 0) + dcd + sl) // ES_ID 0, no flags
        return Box.of("esds").fullHeader(0, 0).bytes(es).build()
    }

    private fun descriptor(tag: Int, body: ByteArray): ByteArray {
        require(body.size < 0x80) { "descriptor too long" }
        return byteArrayOf(tag.toByte(), body.size.toByte()) + body
    }

    private fun u24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun trex(): ByteArray = Box.of("trex")
        .fullHeader(0, 0)
        .u32(AUDIO_TRACK)
        .u32(1)
        .u32(0).u32(0).u32(0)
        .build()

    companion object {
        const val TIMESCALE = Fmp4Writer.TIMESCALE
        /** Its own MediaSource, so the id is free; 2 keeps the door open for a combined stream later. */
        const val AUDIO_TRACK = 2
        private val UNITY_MATRIX = byteArrayOf(
            0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0,
        )
    }
}
