package com.absolutex.remote.smb

import java.io.IOException
import java.io.InputStream

/**
 * Seekable reads over one remote file through a [BlockCache]. Missing ranges are coalesced:
 * one contiguous transport read covers all absent blocks in the span, because on a LAN one
 * larger read is cheaper than N round trips.
 */
class SeekableSmbReader(
    private val transport: SmbTransport,
    private val remotePath: String,
    val sizeBytes: Long,
    private val cache: BlockCache = BlockCache(),
) {
    private val guard = Any()

    /** Transport bytes fetched so far — the test hook proving we never pull the whole file. */
    var bytesFetched: Long = 0
        private set

    /** Transport round trips so far — coalescing is observable here, not on a stopwatch. */
    var readCalls: Int = 0
        private set

    init {
        require(sizeBytes >= 0) { "negative size: $sizeBytes" }
    }

    @Throws(IOException::class)
    fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        if (length == 0) return ByteArray(0)
        if (offset + length > sizeBytes) throw IOException("read past end: $offset+$length > $sizeBytes")
        synchronized(guard) {
            val out = ByteArray(length)
            var done = 0
            while (done < length) {
                val pos = offset + done
                val block = pos / cache.blockSize
                val blockStart = block * cache.blockSize
                var data = cache.get(block)
                if (data == null) {
                    fetchSpan(pos, offset + length)
                    data = cache.get(block)
                        ?: throw IOException("cache miss after fetch at block $block")
                }
                val from = (pos - blockStart).toInt()
                val take = minOf(data.size - from, length - done)
                data.copyInto(out, done, from, from + take)
                done += take
            }
            return out
        }
    }

    /** Entry-data stream: pages decode straight from ranged reads, never a temp file. */
    @Throws(IOException::class)
    fun openStream(offset: Long, length: Long): InputStream {
        require(offset >= 0) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        if (offset + length > sizeBytes) throw IOException("stream past end")
        return RangeInputStream(offset, length)
    }

    /** Drops cached blocks, releasing the working set on book close. */
    fun evictAll() {
        synchronized(guard) { cache.clear() }
    }

    private fun fetchSpan(from: Long, untilExclusive: Long) {
        val firstBlock = from / cache.blockSize
        val lastBlock = (untilExclusive - 1) / cache.blockSize
        var block = firstBlock
        while (block <= lastBlock) {
            if (cache.get(block) == null) {
                var end = block
                while (end + 1 <= lastBlock && cache.get(end + 1) == null) end++
                val start = block * cache.blockSize
                val stop = minOf((end + 1) * cache.blockSize, sizeBytes)
                val bytes = transport.readAt(remotePath, start, (stop - start).toInt())
                bytesFetched += bytes.size
                readCalls++
                var cursor = 0
                var current = block
                while (current <= end) {
                    val piece = minOf(cache.blockSize, bytes.size - cursor)
                    cache.put(current, bytes.copyOfRange(cursor, cursor + piece))
                    cursor += piece
                    current++
                }
                block = end
            }
            block++
        }
    }

    private inner class RangeInputStream(
        private var position: Long,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val byte = readAt(position, 1)
            position++
            remaining--
            return byte[0].toInt() and BYTE_MASK
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            // Chunked through the block cache: one huge readAt would bypass block reuse.
            val take = minOf(len.toLong(), remaining, STREAM_CHUNK_BYTES.toLong()).toInt()
            val bytes = readAt(position, take)
            bytes.copyInto(buffer, off, 0, bytes.size)
            position += bytes.size
            remaining -= bytes.size
            return bytes.size
        }
    }

    companion object {
        private const val BYTE_MASK = 0xFF
        private const val STREAM_CHUNK_BYTES = 32 * 1024
    }
}
