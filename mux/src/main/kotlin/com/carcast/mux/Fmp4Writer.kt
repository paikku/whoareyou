package com.carcast.mux

/**
 * Fragmented MP4 writer tuned for live, low-latency MSE playback:
 *  - `mvhd`/`tkhd`/`mdhd` durations are 0 and there is no `mehd`, so the browser treats the
 *    stream as live (unknown duration) and keeps its render buffer minimal.
 *  - One sample per `moof`, so each encoded frame can be appended the moment it is produced.
 *  - Timescale is microseconds, matching the presentation timestamps on the wire.
 *
 * Track 1 is video (avc1). Audio (mp4a) is added at M6 as track 2.
 */
class Fmp4Writer(private val sps: ByteArray, private val pps: ByteArray) {
    val info: Sps = Sps.parse(sps)
    private var sequence = 0

    val codecString: String get() = info.codecString
    val width: Int get() = info.width
    val height: Int get() = info.height

    fun initSegment(): ByteArray {
        val ftyp = Box.of("ftyp").ascii("isom").u32(0x200).ascii("isom").ascii("iso5").ascii("avc1").ascii("mp41").build()
        val moov = Box.of("moov")
            .child(mvhd())
            .child(trak())
            .child(Box.of("mvex").child(trex(VIDEO_TRACK)).build())
            .build()
        return ftyp + moov
    }

    /** One media segment (`moof` + `mdat`) holding exactly one AVCC sample. */
    fun fragment(ptsUs: Long, durationUs: Long, sample: ByteArray, keyframe: Boolean): ByteArray {
        sequence++
        // The sample flags: is_leading=0, depends_on(2 bits), is_depended_on=0, has_redundancy=0,
        // padding=0, is_non_sync_sample(1 bit), degradation_priority(16 bits).
        val sampleFlags = if (keyframe) 0x02000000 else 0x01010000
        // trun.data_offset is relative to the start of moof: moof size + mdat header (8).
        // moof size is fixed for one sample, compute it by building with a placeholder first.
        val moofWithoutOffset = moof(ptsUs, durationUs, sample.size, sampleFlags, dataOffset = 0)
        val moof = moof(ptsUs, durationUs, sample.size, sampleFlags, dataOffset = moofWithoutOffset.size + 8)
        val mdat = Box.of("mdat").bytes(sample).build()
        return moof + mdat
    }

    private fun moof(ptsUs: Long, durationUs: Long, sampleSize: Int, sampleFlags: Int, dataOffset: Int): ByteArray {
        val mfhd = Box.of("mfhd").fullHeader(0, 0).u32(sequence).build()
        val tfhd = Box.of("tfhd")
            .fullHeader(0, 0x020000) // default-base-is-moof
            .u32(VIDEO_TRACK)
            .build()
        val tfdt = Box.of("tfdt").fullHeader(1, 0).u64(ptsUs).build()
        val trun = Box.of("trun")
            .fullHeader(0, 0x000001 or 0x000100 or 0x000200 or 0x000400) // data-offset, duration, size, flags present
            .u32(1)
            .u32(dataOffset)
            .u32(durationUs)
            .u32(sampleSize)
            .u32(sampleFlags)
            .build()
        val traf = Box.of("traf").child(tfhd).child(tfdt).child(trun).build()
        return Box.of("moof").child(mfhd).child(traf).build()
    }

    private fun mvhd(): ByteArray = Box.of("mvhd")
        .fullHeader(0, 0)
        .u32(0).u32(0)            // creation, modification
        .u32(TIMESCALE)
        .u32(0)                   // duration: unknown (live)
        .u32(0x00010000)          // rate 1.0
        .u16(0x0100)              // volume 1.0
        .zeros(10)
        .bytes(UNITY_MATRIX)
        .zeros(24)                // pre_defined
        .u32(VIDEO_TRACK + 1)     // next_track_ID
        .build()

    private fun trak(): ByteArray = Box.of("trak")
        .child(tkhd())
        .child(mdia())
        .build()

    private fun tkhd(): ByteArray = Box.of("tkhd")
        .fullHeader(0, 0x000007)  // enabled | in movie | in preview
        .u32(0).u32(0)
        .u32(VIDEO_TRACK)
        .u32(0)                   // reserved
        .u32(0)                   // duration
        .zeros(8)
        .u16(0).u16(0)            // layer, alternate_group
        .u16(0).u16(0)            // volume, reserved
        .bytes(UNITY_MATRIX)
        .u32(width shl 16)
        .u32(height shl 16)
        .build()

    private fun mdia(): ByteArray {
        val mdhd = Box.of("mdhd")
            .fullHeader(0, 0)
            .u32(0).u32(0)
            .u32(TIMESCALE)
            .u32(0)               // duration
            .u16(0x55C4)          // language: und
            .u16(0)
            .build()
        val hdlr = Box.of("hdlr")
            .fullHeader(0, 0)
            .u32(0)
            .ascii("vide")
            .zeros(12)
            .ascii("VideoHandler").u8(0)
            .build()
        return Box.of("mdia").child(mdhd).child(hdlr).child(minf()).build()
    }

    private fun minf(): ByteArray {
        val vmhd = Box.of("vmhd").fullHeader(0, 1).zeros(8).build()
        val dref = Box.of("dref").fullHeader(0, 0).u32(1).child(Box.of("url ").fullHeader(0, 1).build()).build()
        val dinf = Box.of("dinf").child(dref).build()
        return Box.of("minf").child(vmhd).child(dinf).child(stbl()).build()
    }

    private fun stbl(): ByteArray {
        val stsd = Box.of("stsd").fullHeader(0, 0).u32(1).child(avc1()).build()
        val empty = { name: String -> Box.of(name).fullHeader(0, 0).u32(0).build() }
        return Box.of("stbl")
            .child(stsd)
            .child(empty("stts"))
            .child(empty("stsc"))
            .child(Box.of("stsz").fullHeader(0, 0).u32(0).u32(0).build())
            .child(empty("stco"))
            .build()
    }

    private fun avc1(): ByteArray = Box.of("avc1")
        .zeros(6).u16(1)          // reserved, data_reference_index
        .zeros(16)                // pre_defined, reserved, pre_defined
        .u16(width).u16(height)
        .u32(0x00480000).u32(0x00480000) // 72 dpi
        .u32(0)
        .u16(1)                   // frame_count
        .zeros(32)                // compressorname
        .u16(0x0018)              // depth
        .u16(0xFFFF)              // pre_defined = -1
        .child(avcC())
        .build()

    private fun avcC(): ByteArray = Box.of("avcC")
        .u8(1)                    // configurationVersion
        .u8(info.profileIdc).u8(info.constraintFlags).u8(info.levelIdc)
        .u8(0xFF)                 // lengthSizeMinusOne = 3 (4-byte NAL lengths)
        .u8(0xE1)                 // numOfSequenceParameterSets = 1
        .u16(sps.size).bytes(sps)
        .u8(1)                    // numOfPictureParameterSets
        .u16(pps.size).bytes(pps)
        .build()

    private fun trex(trackId: Int): ByteArray = Box.of("trex")
        .fullHeader(0, 0)
        .u32(trackId)
        .u32(1)                   // default_sample_description_index
        .u32(0).u32(0).u32(0)     // default duration, size, flags
        .build()

    companion object {
        const val TIMESCALE = 1_000_000
        const val VIDEO_TRACK = 1
        private val UNITY_MATRIX = byteArrayOf(
            0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0,
        )
    }
}
