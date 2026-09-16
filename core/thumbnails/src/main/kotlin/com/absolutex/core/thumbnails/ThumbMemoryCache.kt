package com.absolutex.core.thumbnails

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Byte-accounted memory LRU over decoded thumbnails.
 *
 * Sized in BYTES via [Bitmap.allocationByteCount], not entries: a 512px thumb costs 4x a 256px one
 * at the same config, so counting entries would misjudge residency and blow past the budget.
 */
class ThumbMemoryCache(maxBytes: Long) {

    private val lru = object : LruCache<ThumbRequest, Bitmap>(
        // LruCache is Int-sized; the budget is clamped so a large heap-fraction setting cannot overflow it.
        maxBytes.coerceIn(MIN_BYTES, Int.MAX_VALUE.toLong()).toInt(),
    ) {
        override fun sizeOf(key: ThumbRequest, value: Bitmap): Int = value.allocationByteCount

        override fun entryRemoved(evicted: Boolean, key: ThumbRequest, old: Bitmap, new: Bitmap?) {
            // Never recycle() entries: a bitmap still referenced by the render thread would crash on use.
        }
    }

    operator fun get(key: ThumbRequest): Bitmap? = synchronized(lru) { lru.get(key) }

    fun put(key: ThumbRequest, bitmap: Bitmap) {
        if (bitmap.allocationByteCount <= 0) return
        synchronized(lru) { lru.put(key, bitmap) }
    }

    fun remove(key: ThumbRequest) = synchronized(lru) { lru.remove(key) }

    fun sizeBytes(): Int = synchronized(lru) { lru.size() }

    fun entryCount(): Int = synchronized(lru) { lru.snapshot().size }

    fun clear() = synchronized(lru) { lru.evictAll() }

    fun trimToSize(size: Int) = lru.trimToSize(size)

    companion object {
        private const val MIN_BYTES = 1L
    }
}
