package com.absolutex.remote.core

/**
 * Fixed-size block cache over one remote file's bytes. Sized in BYTES, not entries: the tail
 * block of a file is short, so entry counting would misjudge residency — same argument as
 * TileCache. Blocks are the transfer unit (one RETR/read per missing run), so the cache keys
 * on block index alone; each open file gets its own instance, which makes cross-file
 * isolation structural instead of a keying convention. Synchronized: readers are decode-pool
 * threads plus the UI-driven cover fetch.
 */
class BlockCache(
    val blockSize: Int = DEFAULT_BLOCK_SIZE,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val guard = Any()
    private val blocks = LinkedHashMap<Long, ByteArray>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var heldBytes: Long = 0L

    init {
        require(blockSize > 0) { "blockSize must be positive" }
        require(maxBytes >= blockSize) { "cache must hold at least one block" }
    }

    fun get(blockIndex: Long): ByteArray? {
        synchronized(guard) { return blocks[blockIndex] }
    }

    fun put(blockIndex: Long, block: ByteArray) {
        // Callers chunk to blockSize; an oversized block would silently evict the whole
        // cache, so the contract fails fast instead of degrading into a pass-through.
        require(block.size <= blockSize) { "block larger than blockSize" }
        synchronized(guard) {
            val previous = blocks.put(blockIndex, block)
            heldBytes += (block.size - (previous?.size ?: 0)).toLong()
            evictWhileOverBudget()
        }
    }

    /** Test hook: bytes currently cached. */
    fun sizeBytes(): Long {
        synchronized(guard) { return heldBytes }
    }

    fun clear() {
        synchronized(guard) {
            blocks.clear()
            heldBytes = 0L
        }
    }

    private fun evictWhileOverBudget() {
        val eldest = blocks.entries.iterator()
        // A fresh put lands newest, so eldest-first eviction only reaches it once everything
        // else is gone; a lone over-budget block degrades to an uncached pass-through, never
        // a pin. No production path puts one (readers chunk to blockSize), so this is purely
        // a fail-safe against a misbehaving caller.
        while (heldBytes > maxBytes && eldest.hasNext()) {
            val victim = eldest.next()
            eldest.remove()
            heldBytes -= victim.value.size.toLong()
        }
    }

    companion object {
        // 64 KiB: one ranged read covers a ZIP header run; smaller multiplies round trips on LAN.
        const val DEFAULT_BLOCK_SIZE = 64 * 1024

        // 32 MiB: central directory + several pages resident, never a whole archive.
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
