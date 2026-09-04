package com.carcast.mux

import java.io.ByteArrayOutputStream

/** Tiny ISO BMFF box builder. Everything is big-endian. */
class Box private constructor(private val type: String, private val body: ByteArrayOutputStream) {

    fun u8(v: Int) = apply { body.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }
    fun u24(v: Int) = apply { u8(v ushr 16); u8(v ushr 8); u8(v) }
    fun u32(v: Int) = apply { u8(v ushr 24); u8(v ushr 16); u8(v ushr 8); u8(v) }
    fun u32(v: Long) = u32(v.toInt())
    fun u64(v: Long) = apply { u32((v ushr 32).toInt()); u32(v.toInt()) }
    fun bytes(b: ByteArray) = apply { body.write(b) }
    fun ascii(s: String) = bytes(s.toByteArray(Charsets.US_ASCII))
    fun zeros(n: Int) = apply { repeat(n) { body.write(0) } }
    fun fullHeader(version: Int, flags: Int) = apply { u8(version); u24(flags) }
    fun child(box: ByteArray) = bytes(box)

    fun build(): ByteArray {
        val content = body.toByteArray()
        val size = 8 + content.size
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte(); out[1] = (size ushr 16).toByte(); out[2] = (size ushr 8).toByte(); out[3] = size.toByte()
        val t = type.toByteArray(Charsets.US_ASCII)
        System.arraycopy(t, 0, out, 4, 4)
        System.arraycopy(content, 0, out, 8, content.size)
        return out
    }

    companion object {
        fun of(type: String): Box {
            require(type.length == 4)
            return Box(type, ByteArrayOutputStream(64))
        }
    }
}
