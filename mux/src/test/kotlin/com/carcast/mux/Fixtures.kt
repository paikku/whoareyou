package com.carcast.mux

import java.io.File

object Fixtures {
    /** The committed test clip: 8 s, 1280x720, 30 fps, baseline, GOP 30. */
    val clip: File by lazy {
        val candidates = listOf("../tools/clips/test-720p30.h264", "tools/clips/test-720p30.h264")
        candidates.map { File(it) }.firstOrNull { it.exists() } ?: error("test clip not found")
    }
    val nals: List<ByteArray> by lazy { AnnexB.splitNalUnits(clip.readBytes()) }
    val sps: ByteArray by lazy { nals.first { AnnexB.nalType(it) == AnnexB.NAL_SPS } }
    val pps: ByteArray by lazy { nals.first { AnnexB.nalType(it) == AnnexB.NAL_PPS } }
}
