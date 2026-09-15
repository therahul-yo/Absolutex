package com.absolutex.remote.ftp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtpBlockCacheTest {

    private fun block(seed: Byte, size: Int = 4): ByteArray = ByteArray(size) { (seed + it).toByte() }

    @Test fun `miss then hit`() {
        val cache = FtpBlockCache(blockSize = 4, maxBytes = 8)
        assertNull(cache.get("/b.cbz", 0))
        cache.put("/b.cbz", 0, block(1))
        assertArrayEquals(block(1), cache.get("/b.cbz", 0))
    }

    @Test fun `eldest evicted once over budget with bytes re-accounted`() {
        val cache = FtpBlockCache(blockSize = 4, maxBytes = 8)
        cache.put("/b.cbz", 0, block(1))
        cache.put("/b.cbz", 1, block(2))
        cache.put("/b.cbz", 2, block(3))
        assertNull(cache.get("/b.cbz", 0))
        assertArrayEquals(block(2), cache.get("/b.cbz", 1))
        assertArrayEquals(block(3), cache.get("/b.cbz", 2))
        assertEquals(8L, cache.bytesHeld())
        assertEquals(2, cache.entryCount())
    }

    @Test fun `overwrite keeps byte accounting flat`() {
        val cache = FtpBlockCache(blockSize = 4, maxBytes = 8)
        cache.put("/b.cbz", 0, block(1))
        cache.put("/b.cbz", 0, block(9))
        assertArrayEquals(block(9), cache.get("/b.cbz", 0))
        assertEquals(4L, cache.bytesHeld())
        assertEquals(1, cache.entryCount())
    }

    @Test fun `lone over-budget block degrades to a pass-through`() {
        val cache = FtpBlockCache(blockSize = 4, maxBytes = 2)
        cache.put("/b.cbz", 0, block(1))
        assertNull(cache.get("/b.cbz", 0))
        assertEquals(0L, cache.bytesHeld())
    }

    @Test fun `same index on different paths is isolated`() {
        val cache = FtpBlockCache(blockSize = 4, maxBytes = 16)
        cache.put("/a.cbz", 0, block(1))
        cache.put("/b.cbz", 0, block(2))
        assertArrayEquals(block(1), cache.get("/a.cbz", 0))
        assertArrayEquals(block(2), cache.get("/b.cbz", 0))
    }
}
