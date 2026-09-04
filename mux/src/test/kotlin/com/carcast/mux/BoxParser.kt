package com.carcast.mux

import java.nio.ByteBuffer

/** Just enough parsing to assert on the writer's output in tests. */
class ParsedBox(val type: String, val start: Int, val size: Int, val data: ByteArray) {
    val payload: ByteArray get() = data.copyOfRange(start + 8, start + size)

    fun children(headerSkip: Int = 0): List<ParsedBox> = parseBoxes(data, start + 8 + headerSkip, start + size)

    fun child(type: String, headerSkip: Int = 0): ParsedBox =
        children(headerSkip).firstOrNull { it.type == type } ?: error("no $type inside ${this.type}")

    fun u32(offset: Int): Long = ByteBuffer.wrap(payload).getInt(offset).toLong() and 0xFFFFFFFFL
    fun u64(offset: Int): Long = ByteBuffer.wrap(payload).getLong(offset)
    fun u16(offset: Int): Int = ByteBuffer.wrap(payload).getShort(offset).toInt() and 0xFFFF
}

fun parseBoxes(data: ByteArray, from: Int = 0, to: Int = data.size): List<ParsedBox> {
    val out = ArrayList<ParsedBox>()
    var p = from
    while (p + 8 <= to) {
        val size = ByteBuffer.wrap(data).getInt(p)
        val type = String(data, p + 4, 4, Charsets.US_ASCII)
        require(size >= 8 && p + size <= to) { "bad box $type size=$size at $p" }
        out += ParsedBox(type, p, size, data)
        p += size
    }
    require(p == to) { "trailing bytes" }
    return out
}
