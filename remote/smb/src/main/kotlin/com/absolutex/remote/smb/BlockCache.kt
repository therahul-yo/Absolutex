package com.absolutex.remote.smb

/**
 * Fixed-size block cache over remote bytes. Sized in BYTES, not entries: the tail block of a
 * file is short, so entry counting would misjudge residency — same argument as TileCache.
 * Synchronized: readers are decode-pool threads plus the UI-driven cover fetch.
 */
class BlockCache(
    val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val guard = Any()
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, ByteArray>): Boolean =
            sizeBytesLocked() > maxBytes
    }

    init {
        require(blockSize > 0) { "blockSize must be positive" }
        require(maxBytes >= blockSize) { "cache must hold at least one block" }
    }

    fun get(blockIndex: Long): ByteArray? {
        synchronized(guard) { return blocks[blockIndex] }
    }

    fun put(blockIndex: Long, data: ByteArray) {
        require(data.size <= blockSize) { "block larger than blockSize" }
        synchronized(guard) { blocks[blockIndex] = data }
    }

    fun sizeBytes(): Long {
        synchronized(guard) { return sizeBytesLocked() }
    }

    fun clear() {
        synchronized(guard) { blocks.clear() }
    }

    private fun sizeBytesLocked(): Long = blocks.values.sumOf { it.size.toLong() }

    companion object {
        // 64 KiB: one SMB read covers a ZIP header run; smaller multiplies round trips on LAN.
        const val DEFAULT_BLOCK_SIZE = 64 * 1024
        // 32 MiB: central directory + several pages resident, never a whole archive.
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
        private const val LOAD_FACTOR = 0.75f
    }
}
