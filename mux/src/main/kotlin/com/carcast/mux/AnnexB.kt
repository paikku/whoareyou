package com.carcast.mux

/** H.264 Annex-B helpers: start-code splitting and access-unit grouping. */
object AnnexB {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    fun nalType(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    fun isVcl(nal: ByteArray): Boolean = nalType(nal) in 1..5

    /** Splits a byte stream on 3/4-byte start codes. Returned NAL units have no start code. */
    fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        val n = data.size
        while (i + 2 < n) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) {
                    var end = i
                    // A 4-byte start code has a zero before the 001; do not include it in the previous NAL.
                    if (end > start && data[end - 1].toInt() == 0) end--
                    if (end > start) out += data.copyOfRange(start, end)
                }
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start >= 0 && start < n) out += data.copyOfRange(start, n)
        return out
    }

    /**
     * True when this VCL NAL starts a new picture (first_mb_in_slice == 0, i.e. the first
     * Exp-Golomb value after the NAL header is 0 -> its first bit is 1).
     */
    fun isFirstSliceOfPicture(nal: ByteArray): Boolean =
        isVcl(nal) && nal.size > 1 && (nal[1].toInt() and 0x80) != 0

    /** Groups NAL units into access units. Non-VCL NALs before the first slice belong to that picture. */
    fun groupAccessUnits(nals: List<ByteArray>): List<List<ByteArray>> {
        val units = ArrayList<List<ByteArray>>()
        var current = ArrayList<ByteArray>()
        var currentHasVcl = false
        for (nal in nals) {
            val vcl = isVcl(nal)
            val startsNew = if (vcl) currentHasVcl && isFirstSliceOfPicture(nal) else currentHasVcl
            if (startsNew) {
                units += current
                current = ArrayList()
                currentHasVcl = false
            }
            current += nal
            if (vcl) currentHasVcl = true
        }
        if (current.isNotEmpty()) units += current
        return units
    }

    fun isKeyframe(accessUnit: List<ByteArray>): Boolean = accessUnit.any { nalType(it) == NAL_IDR }

    /** Converts an access unit into an AVCC sample: [u32 length][nal]... without SPS/PPS/AUD. */
    fun toAvccSample(accessUnit: List<ByteArray>): ByteArray {
        val keep = accessUnit.filter { nalType(it) != NAL_SPS && nalType(it) != NAL_PPS && nalType(it) != NAL_AUD }
        val size = keep.sumOf { 4 + it.size }
        val out = ByteArray(size)
        var o = 0
        for (nal in keep) {
            val len = nal.size
            out[o] = (len ushr 24).toByte(); out[o + 1] = (len ushr 16).toByte()
            out[o + 2] = (len ushr 8).toByte(); out[o + 3] = len.toByte()
            System.arraycopy(nal, 0, out, o + 4, len)
            o += 4 + len
        }
        return out
    }
}
