package com.carcast.mux

import java.io.DataOutputStream
import java.io.File

/**
 * mux <input.h264> <output.cmp4> [fps]
 *   Converts a raw Annex-B H.264 elementary stream (constant frame rate) into the record stream
 *   the app and the fake phone replay.
 * mux <input.aac> <output.cmp4>
 *   Same for an ADTS AAC file: init segment + one fragment per AAC frame, every packet a keyframe.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: mux <input.h264|input.aac> <output.cmp4> [fps]")
        kotlin.system.exitProcess(2)
    }
    if (args[0].endsWith(".aac")) muxAudio(File(args[0]), File(args[1])) else muxVideo(File(args[0]), File(args[1]), args.getOrNull(2)?.toDouble() ?: 30.0)
}

private fun muxVideo(input: File, output: File, fps: Double) {
    val data = input.readBytes()
    val nals = AnnexB.splitNalUnits(data)
    val sps = nals.first { AnnexB.nalType(it) == AnnexB.NAL_SPS }
    val pps = nals.first { AnnexB.nalType(it) == AnnexB.NAL_PPS }
    val writer = Fmp4Writer(sps, pps)
    val units = AnnexB.groupAccessUnits(nals).filter { au -> au.any { AnnexB.isVcl(it) } }
    val frameUs = (1_000_000.0 / fps).toLong()

    DataOutputStream(output.outputStream().buffered()).use { out ->
        StreamRecord.write(out, StreamRecord.TYPE_INIT, 0, writer.initSegment())
        units.forEachIndexed { i, au ->
            val key = AnnexB.isKeyframe(au)
            val pts = i * frameUs
            val frag = writer.fragment(pts, frameUs, AnnexB.toAvccSample(au), key)
            StreamRecord.write(out, if (key) StreamRecord.TYPE_KEY else StreamRecord.TYPE_FRAME, pts, frag)
        }
    }
    println("$output: ${units.size} frames @ ${fps}fps, ${writer.width}x${writer.height} ${writer.codecString}")
}

private fun muxAudio(input: File, output: File) {
    val frames = Adts.parse(input.readBytes())
    val first = frames.first()
    val writer = Fmp4AudioWriter(Adts.audioSpecificConfig(first.profile, first.sampleRateIndex, first.channels))
    DataOutputStream(output.outputStream().buffered()).use { out ->
        StreamRecord.write(out, StreamRecord.TYPE_INIT, 0, writer.initSegment())
        frames.forEachIndexed { i, f ->
            val pts = i * writer.frameDurationUs
            StreamRecord.write(out, StreamRecord.TYPE_KEY, pts, writer.fragment(pts, f.data))
        }
    }
    println("$output: ${frames.size} AAC frames, ${writer.sampleRate} Hz ${writer.channels}ch ${writer.codecString}, ${frames.size * writer.frameDurationUs / 1000} ms")
}
