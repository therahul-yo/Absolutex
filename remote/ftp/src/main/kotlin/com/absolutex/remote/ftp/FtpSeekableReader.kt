package com.absolutex.remote.ftp

import java.io.IOException

/**
 * Seekable range reader over one remote file: block cache plus missing-run coalescing.
 *
 * FTP has no random-read primitive, so each [read] maps to the fewest RETRs covering the
 * missing blocks — one per contiguous run — and [bytesFetched]/[readCalls] expose that cost as
 * test hooks. Synchronized: one reader per file serialises transfers while the transport lock
 * nests strictly inside, so the order never inverts.
 *
 * TODO(remote-core): dedup with :remote:smb once both lanes merge — move this file and
 * `FtpBlockCache.kt` into a shared `:remote:core` module behind a transport-neutral range
 * interface, and delete the copies. Duplicated here by necessity: `:remote:smb` is an unmerged
 * PR this lane must not depend on, and the merge should keep one block cache plus one
 * coalescing reader (this file, `FtpBlockCache.kt`, and their Smb-side counterparts).
 */
class FtpSeekableReader(
    private val transport: FtpTransport,
    private val path: String,
    val size: Long,
    private val cache: FtpBlockCache = FtpBlockCache(),
) {
    /** Total payload bytes pulled over RETR since construction. Test hook. */
    var bytesFetched: Long = 0L
        private set

    /** RETR round trips issued since construction. Test hook. */
    var readCalls: Int = 0
        private set

    /** Exactly [length] bytes from [offset], or throws [IOException]. */
    @Throws(IOException::class)
    @Synchronized
    fun read(offset: Long, length: Int): ByteArray {
        require(offset >= MIN_OFFSET) { "negative read offset: $offset" }
        require(length >= MIN_LENGTH) { "negative read length: $length" }
        if (offset > size || length > size - offset) throw IOException(pastEndMessage(offset, length))
        return when {
            length == EMPTY_LENGTH -> ByteArray(EMPTY_LENGTH)
            // A read wider than the whole cache can never survive a round trip through it: its
            // own earliest blocks get evicted (by its own later blocks) before assemble() reads
            // them back — "cache miss after fetch". Bypass the cache for these and assemble
            // straight from the fetched buffers instead; nothing this wide benefits from caching.
            length > cache.maxBytes -> fetchDirect(offset, length)
            else -> {
                fetchMissing(offset, length)
                assemble(offset, length)
            }
        }
    }

    private fun fetchDirect(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val chunk = minOf(FETCH_MAX_BYTES, (length - done).toLong()).toInt()
            val bytes = transport.readAt(path, offset + done, chunk)
            bytesFetched += bytes.size.toLong()
            readCalls++
            bytes.copyInto(out, done)
            done += bytes.size
        }
        return out
    }

    private fun fetchMissing(offset: Long, length: Int) {
        val blockSize = cache.blockSize.toLong()
        val first = offset / blockSize
        val last = (offset + length - 1) / blockSize
        var cursor = first
        while (cursor <= last) {
            if (cache.get(path, cursor) != null) {
                cursor++
                continue
            }
            val runEnd = contiguousMissingEnd(cursor, last)
            fetchRun(cursor, runEnd, blockSize)
            cursor = runEnd + 1
        }
    }

    private fun contiguousMissingEnd(from: Long, last: Long): Long {
        var end = from
        while (end < last && cache.get(path, end + 1) == null) end++
        return end
    }

    private fun fetchRun(first: Long, last: Long, blockSize: Long) {
        var cursor = first
        while (cursor <= last) {
            // One RETR's buffer stays bounded however wide the run is; wide runs chunk in
            // block-aligned windows, so every chunk splits cleanly into whole cache blocks.
            val windowEnd = minOf(cursor + FETCH_BLOCKS - 1, last)
            val startByte = cursor * blockSize
            val endByte = minOf((windowEnd + 1) * blockSize, size)
            val bytes = transport.readAt(path, startByte, (endByte - startByte).toInt())
            bytesFetched += bytes.size.toLong()
            readCalls++
            splitIntoCache(cursor, bytes, blockSize)
            cursor = windowEnd + 1
        }
    }

    private fun splitIntoCache(first: Long, bytes: ByteArray, blockSize: Long) {
        var done = 0
        var index = first
        // One copy per block keeps cache ownership simple; the RETR buffer itself is garbage.
        while (done < bytes.size) {
            val take = minOf(blockSize, (bytes.size - done).toLong()).toInt()
            cache.put(path, index, bytes.copyOfRange(done, done + take))
            done += take
            index++
        }
    }

    private fun assemble(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        val blockSize = cache.blockSize
        // copyInto assembles without intermediate buffers: one pass from cached blocks to result.
        while (done < length) {
            val absolute = offset + done
            val block = cache.get(path, absolute / blockSize) ?: fail(afterFetchMessage(absolute))
            val from = (absolute % blockSize).toInt()
            val take = minOf(block.size - from, length - done)
            block.copyInto(out, done, from, from + take)
            done += take
        }
        return out
    }

    private fun pastEndMessage(offset: Long, length: Int): String =
        "read past end: offset=$offset length=$length size=$size path=$path"

    private fun afterFetchMessage(absolute: Long): String =
        "cache miss after fetch: file offset $absolute path=$path"

    private fun fail(message: String): Nothing = throw IOException(message)

    companion object {
        private const val MIN_OFFSET = 0L
        private const val MIN_LENGTH = 0
        private const val EMPTY_LENGTH = 0

        /** One RETR never buffers more than this; 8 MiB is 128 blocks of headroom-free RAM. */
        private const val FETCH_MAX_BYTES = 8_388_608L
        private const val FETCH_BLOCKS = FETCH_MAX_BYTES / FtpBlockCache.BLOCK_SIZE
    }
}
