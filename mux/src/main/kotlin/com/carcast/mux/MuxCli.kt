package com.carcast.mux

import java.io.DataOutputStream
import java.io.File

/**
 * mux <input.h264> <output.cmp4> [fps]
 * Converts a raw Annex-B H.264 elementary stream (constant frame rate) into the record stream
 * the app and the fake phone replay.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: mux <input.h264> <output.cmp4> [fps]")
        kotlin.system.exitProcess(2)
    }
    val fps = args.getOrNull(2)?.toDouble() ?: 30.0
    val data = File(args[0]).readBytes()
    val nals = AnnexB.splitNalUnits(data)
    val sps = nals.first { AnnexB.nalType(it) == AnnexB.NAL_SPS }
    val pps = nals.first { AnnexB.nalType(it) == AnnexB.NAL_PPS }
    val writer = Fmp4Writer(sps, pps)
    val units = AnnexB.groupAccessUnits(nals).filter { au -> au.any { AnnexB.isVcl(it) } }
    val frameUs = (1_000_000.0 / fps).toLong()

    DataOutputStream(File(args[1]).outputStream().buffered()).use { out ->
        StreamRecord.write(out, StreamRecord.TYPE_INIT, 0, writer.initSegment())
        units.forEachIndexed { i, au ->
            val key = AnnexB.isKeyframe(au)
            val pts = i * frameUs
            val frag = writer.fragment(pts, frameUs, AnnexB.toAvccSample(au), key)
            StreamRecord.write(out, if (key) StreamRecord.TYPE_KEY else StreamRecord.TYPE_FRAME, pts, frag)
        }
    }
    println("${args[1]}: ${units.size} frames @ ${fps}fps, ${writer.width}x${writer.height} ${writer.codecString}")
}
