package com.absolutex.remote.smb

import java.io.IOException

/**
 * In-memory [SmbTransport] recording every range. The test hook for the lane's core claim:
 * indexing + one page + cover must traffic a fraction of the file, never the whole thing.
 */
class FakeSmbTransport(private val files: Map<String, ByteArray>) : SmbTransport {
    data class Range(val path: String, val offset: Long, val length: Int)

    val ranges = ArrayList<Range>()

    val bytesServed: Long get() = ranges.sumOf { it.length.toLong() }

    var closes = 0
        private set

    override fun sizeBytes(remotePath: String): Long =
        files[remotePath]?.size?.toLong() ?: throw IOException("no such file: $remotePath")

    override fun readAt(remotePath: String, offset: Long, length: Int): ByteArray {
        val bytes = files[remotePath] ?: throw IOException("no such file: $remotePath")
        if (offset + length > bytes.size) throw IOException("read past end")
        ranges.add(Range(remotePath, offset, length))
        return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    override fun close() {
        closes++
    }
}
