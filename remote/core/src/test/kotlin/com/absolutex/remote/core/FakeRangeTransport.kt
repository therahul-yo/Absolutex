package com.absolutex.remote.core

import java.io.IOException

/** In-memory [RangeTransport] recording every range. */
class FakeRangeTransport(private val bytes: ByteArray) : RangeTransport {
    data class Range(val offset: Long, val length: Int)

    val ranges = ArrayList<Range>()

    val bytesServed: Long get() = ranges.sumOf { it.length.toLong() }

    var closes = 0
        private set

    override fun sizeBytes(): Long = bytes.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset + length > bytes.size) throw IOException("read past end")
        ranges.add(Range(offset, length))
        return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    override fun close() {
        closes++
    }
}
