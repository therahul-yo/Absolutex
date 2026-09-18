package com.absolutex.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BlockCacheTest {

    @Test fun `hit returns stored block`() {
        val cache = BlockCache(blockSize = 8, maxBytes = 64)
        cache.put(3, byteArrayOf(1, 2, 3))
        assertTrue(cache.get(3)?.contentEquals(byteArrayOf(1, 2, 3)) == true)
    }

    @Test fun `miss returns null`() {
        assertNull(BlockCache(blockSize = 8, maxBytes = 64).get(0))
    }

    @Test fun `evicts least-recently-used first under byte pressure`() {
        val cache = BlockCache(blockSize = 4, maxBytes = 8)
        cache.put(0, ByteArray(4))
        cache.put(1, ByteArray(4))
        cache.get(0)
        cache.put(2, ByteArray(4))
        assertTrue(cache.get(0) != null)
        assertNull(cache.get(1))
        assertTrue(cache.get(2) != null)
    }

    @Test fun `byte accounting counts short tail blocks exactly`() {
        val cache = BlockCache(blockSize = 8, maxBytes = 64)
        cache.put(0, ByteArray(8))
        cache.put(1, ByteArray(3))
        assertEquals(11, cache.sizeBytes())
    }

    @Test fun `overwrite keeps byte accounting flat`() {
        val cache = BlockCache(blockSize = 8, maxBytes = 64)
        cache.put(0, ByteArray(8))
        cache.put(0, ByteArray(5))
        assertEquals(5, cache.sizeBytes())
        assertTrue(cache.get(0)?.size == 5)
    }

    @Test fun `oversize block is rejected`() {
        // No production path puts one (readers chunk to blockSize); without the guard a
        // misbehaving caller would silently evict the whole cache.
        val cache = BlockCache(blockSize = 8, maxBytes = 16)
        try {
            cache.put(0, ByteArray(64))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("blockSize") == true)
        }
        assertNull(cache.get(0))
        assertEquals(0, cache.sizeBytes())
    }
}
