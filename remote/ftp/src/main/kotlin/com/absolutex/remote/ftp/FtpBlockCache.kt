package com.absolutex.remote.ftp

/**
 * Fixed-window block cache: 64 KiB blocks under a 32 MiB byte-accounted LRU cap.
 *
 * Blocks are the FTP transfer unit — one RETR per missing run — so the cache keys on
 * (path, block index) and evicts eldest-first while over budget. All methods are synchronized;
 * [FtpSeekableReader]'s own lock nests outside this one, never inside, so the order is fixed.
 */
class FtpBlockCache(
    val blockSize: Int = BLOCK_SIZE,
    val maxBytes: Long = MAX_BYTES,
) {
    private data class Key(val path: String, val index: Long)

    // Access-order LinkedHashMap iterates eldest-first, which is exactly the eviction order, and
    // one tiny Key alloc per op is noise next to the RETR it gates.
    private val blocks = LinkedHashMap<Key, ByteArray>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var heldBytes: Long = 0L

    /** Test hook: bytes currently cached. */
    @Synchronized
    fun bytesHeld(): Long = heldBytes

    /** Test hook: blocks currently cached. */
    @Synchronized
    fun entryCount(): Int = blocks.size

    @Synchronized
    fun get(path: String, index: Long): ByteArray? = blocks[Key(path, index)]

    @Synchronized
    fun put(path: String, index: Long, block: ByteArray) {
        require(block.size <= blockSize) { "oversize block: ${block.size} > $blockSize" }
        val previous = blocks.put(Key(path, index), block)
        heldBytes += (block.size - (previous?.size ?: EMPTY_SIZE)).toLong()
        evictWhileOverBudget()
    }

    private fun evictWhileOverBudget() {
        val eldest = blocks.entries.iterator()
        // A fresh put lands newest, so eldest-first eviction only reaches it once everything else
        // is gone; a lone over-budget block degrades to an uncached pass-through, never a pin.
        while (heldBytes > maxBytes && eldest.hasNext()) {
            val victim = eldest.next()
            eldest.remove()
            heldBytes -= victim.value.size.toLong()
        }
    }

    companion object {
        /** One RETR carries whole blocks; 64 KiB keeps round trips coarse without hogging RAM. */
        const val BLOCK_SIZE = 65_536

        /** 32 MiB holds ~512 blocks — ample readahead for a comic, bounded for a phone. */
        const val MAX_BYTES = 33_554_432L
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
        private const val EMPTY_SIZE = 0
    }
}
