package com.carcast.mux

/** Enough of the H.264 SPS to know the coded picture size and the codec string. */
class Sps(val profileIdc: Int, val constraintFlags: Int, val levelIdc: Int, val width: Int, val height: Int) {

    /** RFC 6381 codec string as used by MSE, e.g. avc1.42E01F. */
    val codecString: String
        get() = "avc1.%02X%02X%02X".format(profileIdc, constraintFlags, levelIdc)

    companion object {
        /** Parses an SPS NAL unit (with its 1-byte header, without start code). */
        fun parse(nal: ByteArray): Sps {
            require(AnnexB.nalType(nal) == AnnexB.NAL_SPS) { "not an SPS" }
            val r = BitReader(unescape(nal, 1))
            val profileIdc = r.bits(8)
            val constraintFlags = r.bits(8)
            val levelIdc = r.bits(8)
            r.ue() // seq_parameter_set_id
            var chromaFormatIdc = 1
            var separateColourPlane = 0
            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                chromaFormatIdc = r.ue()
                if (chromaFormatIdc == 3) separateColourPlane = r.bits(1)
                r.ue() // bit_depth_luma_minus8
                r.ue() // bit_depth_chroma_minus8
                r.bits(1) // qpprime_y_zero_transform_bypass_flag
                if (r.bits(1) == 1) { // seq_scaling_matrix_present_flag
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until count) {
                        if (r.bits(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                    }
                }
            }
            r.ue() // log2_max_frame_num_minus4
            val pocType = r.ue()
            if (pocType == 0) {
                r.ue()
            } else if (pocType == 1) {
                r.bits(1)
                r.se(); r.se()
                val n = r.ue()
                repeat(n) { r.se() }
            }
            r.ue() // max_num_ref_frames
            r.bits(1) // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbs = r.ue() + 1
            val picHeightInMapUnits = r.ue() + 1
            val frameMbsOnly = r.bits(1)
            if (frameMbsOnly == 0) r.bits(1) // mb_adaptive_frame_field_flag
            r.bits(1) // direct_8x8_inference_flag
            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (r.bits(1) == 1) { // frame_cropping_flag
                cropLeft = r.ue(); cropRight = r.ue(); cropTop = r.ue(); cropBottom = r.ue()
            }
            val chromaArrayType = if (separateColourPlane == 1) 0 else chromaFormatIdc
            val (subWidthC, subHeightC) = when (chromaFormatIdc) {
                1 -> 2 to 2
                2 -> 2 to 1
                else -> 1 to 1
            }
            val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
            val cropUnitY = (if (chromaArrayType == 0) 1 else subHeightC) * (2 - frameMbsOnly)
            val width = picWidthInMbs * 16 - cropUnitX * (cropLeft + cropRight)
            val height = (2 - frameMbsOnly) * picHeightInMapUnits * 16 - cropUnitY * (cropTop + cropBottom)
            return Sps(profileIdc, constraintFlags, levelIdc, width, height)
        }

        private fun skipScalingList(r: BitReader, size: Int) {
            var last = 8
            var next = 8
            for (j in 0 until size) {
                if (next != 0) {
                    val delta = r.se()
                    next = (last + delta + 256) % 256
                }
                last = if (next == 0) last else next
            }
        }

        /** Removes emulation-prevention bytes (00 00 03 -> 00 00). */
        fun unescape(nal: ByteArray, offset: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(nal.size)
            var zeros = 0
            for (i in offset until nal.size) {
                val b = nal[i].toInt() and 0xFF
                if (zeros >= 2 && b == 3) { zeros = 0; continue }
                out.write(b)
                zeros = if (b == 0) zeros + 1 else 0
            }
            return out.toByteArray()
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = data[pos ushr 3].toInt() and 0xFF
                val bit = (byte ushr (7 - (pos and 7))) and 1
                v = (v shl 1) or bit
                pos++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) { zeros++; require(zeros < 32) { "bad exp-golomb" } }
            return if (zeros == 0) 0 else ((1 shl zeros) - 1) + bits(zeros)
        }
        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}
