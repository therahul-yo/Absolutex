package com.absolutex.core.decode

import android.graphics.Bitmap
import android.util.LruCache

data class TileKey(
    val pageIndex: Int,
    val col: Int,
    val row: Int,
    val sampleSize: Int,
    /** Book identity scoping the tile. Default "" keeps older call sites compiling. */
    val bookId: String = "",
)

/**
 * Byte-accounted LRU over decoded tiles.
 *
 * Sized in BYTES, not entries: a 512px tile at sampleSize 1 costs 1 MB while the same tile at
 * sampleSize 8 costs 16 KB, so counting entries would misjudge residency by ~64x and either
 * waste the budget or blow past it.
 *
 * Hardware bitmaps report their true footprint through allocationByteCount even though the
 * pixels live in graphics memory rather than the Java heap, so the accounting stays honest.
 */
class TileCache(maxBytes: Long) {

    private val lru = object : LruCache<TileKey, Bitmap>(
        // LruCache is Int-sized; the budget is clamped so a large user-set cache cannot overflow.
        maxBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
    ) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.allocationByteCount

        override fun entryRemoved(evicted: Boolean, key: TileKey, old: Bitmap, new: Bitmap?) {
            // Never recycle a bitmap that is still being drawn this frame. Hardware bitmaps are
            // freed by the GC once the last reference drops, and an explicit recycle() here
            // would race the render thread and crash on a tile still on screen.
        }
    }

    operator fun get(key: TileKey): Bitmap? = synchronized(lru) { lru.get(key) }

    fun put(key: TileKey, bitmap: Bitmap) {
        if (bitmap.allocationByteCount <= 0) return
        synchronized(lru) { lru.put(key, bitmap) }
    }

    fun sizeBytes(): Int = synchronized(lru) { lru.size() }
    fun maxBytes(): Int = synchronized(lru) { lru.maxSize() }

    /** Drops everything. Used when the reader closes a book, not between pages. */
    fun clear() = synchronized(lru) { lru.evictAll() }
}
