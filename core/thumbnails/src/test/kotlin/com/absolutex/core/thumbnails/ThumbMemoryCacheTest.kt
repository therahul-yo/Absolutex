package com.absolutex.core.thumbnails

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Memory accounting runs on the JVM here through Robolectric: the assertions are about byte
 * budgets and LRU order, which the shadows report faithfully.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbMemoryCacheTest {

    private fun key(page: Int) = ThumbRequest("book-a", page, ThumbRequest.BUCKET_SMALL)

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    @Test fun `put then get returns the same instance`() {
        val cache = ThumbMemoryCache(1024L * 1024L)
        val thumb = bitmap(24, 12)
        cache.put(key(0), thumb)
        assertSame(thumb, cache.get(key(0)))
    }

    @Test fun `missing keys read as null`() {
        assertNull(ThumbMemoryCache(1024L * 1024L).get(key(0)))
    }

    @Test fun `size accounting follows allocationByteCount`() {
        val cache = ThumbMemoryCache(1024L * 1024L)
        val thumb = bitmap(24, 12)
        cache.put(key(0), thumb)
        assertEquals(thumb.allocationByteCount, cache.sizeBytes())
        assertEquals(1, cache.entryCount())
    }

    @Test fun `eviction is by bytes, oldest first`() {
        val one = bitmap(16, 16)
        val cache = ThumbMemoryCache(one.allocationByteCount.toLong())
        cache.put(key(0), one)
        cache.put(key(1), bitmap(16, 16))
        assertNull(cache.get(key(0)))
        assertEquals(1, cache.entryCount())
        assertTrue(cache.sizeBytes() <= one.allocationByteCount)
    }

    @Test fun `a fresh get keeps a hot entry resident`() {
        val one = bitmap(16, 16)
        val cache = ThumbMemoryCache((one.allocationByteCount * 2).toLong())
        cache.put(key(0), one)
        cache.put(key(1), bitmap(16, 16))
        cache.get(key(0))
        cache.put(key(2), bitmap(16, 16))
        assertEquals(one, cache.get(key(0)))
    }

    @Test fun `an entry larger than the budget never lands`() {
        val cache = ThumbMemoryCache(1L)
        cache.put(key(0), bitmap(16, 16))
        assertNull(cache.get(key(0)))
        assertEquals(0, cache.entryCount())
    }

    @Test fun `remove drops one entry`() {
        val cache = ThumbMemoryCache(1024L * 1024L)
        cache.put(key(0), bitmap(8, 8))
        cache.put(key(1), bitmap(8, 8))
        cache.remove(key(0))
        assertNull(cache.get(key(0)))
        assertEquals(1, cache.entryCount())
    }

    @Test fun `clear drops everything`() {
        val cache = ThumbMemoryCache(1024L * 1024L)
        cache.put(key(0), bitmap(8, 8))
        cache.clear()
        assertNull(cache.get(key(0)))
        assertEquals(0, cache.sizeBytes())
    }
}
