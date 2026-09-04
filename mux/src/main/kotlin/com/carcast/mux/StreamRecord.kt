package com.carcast.mux

import java.io.DataOutputStream

/**
 * Record format of the pre-muxed clip files (tools/clips/assets/clips, .cmp4 files) consumed by the
 * app's ClipSource and by tools/fake-phone: [u8 type][u64 pts_us][u32 len][payload].
 */
object StreamRecord {
    const val TYPE_INIT = 0
    const val TYPE_FRAME = 1
    const val TYPE_KEY = 2

    fun write(out: DataOutputStream, type: Int, ptsUs: Long, payload: ByteArray) {
        out.writeByte(type)
        out.writeLong(ptsUs)
        out.writeInt(payload.size)
        out.write(payload)
    }
}
