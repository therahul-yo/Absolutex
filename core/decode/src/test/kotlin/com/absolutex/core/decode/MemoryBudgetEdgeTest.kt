package com.absolutex.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4: clamp/edge coverage for [MemoryBudget] (pure JVM, no device needed).
 *
 * NOTE on [TileCache]: it wraps android.util.LruCache over android.graphics.Bitmap, so it
 * cannot run as a local JVM test (no Robolectric in this repo by design — §3 keeps the test
 * path honest). Its byte-accounting contract is exercised on-device via the reader benchmark;
 * do NOT add a fake-Bitmap unit test here, it would prove nothing about allocationByteCount.
 */
class MemoryBudgetEdgeTest {

    private val gb = 1024L * 1024 * 1024

    @Test fun `zero or negative RAM clamps to the floor, never zero or negative`() {
        assertEquals(256L * 1024 * 1024, MemoryBudget.defaultCacheBytes(0))
        assertEquals(256L * 1024 * 1024, MemoryBudget.defaultCacheBytes(-1))
        assertEquals(256L * 1024 * 1024, MemoryBudget.defaultCacheBytes(Long.MIN_VALUE))
    }

    @Test fun `absurd RAM clamps to the ceiling`() {
        assertEquals(4 * gb, MemoryBudget.defaultCacheBytes(Long.MAX_VALUE))
    }

    @Test fun `fraction holds just above the floor`() {
        // Just above 256MB/0.15 ~= 1.7GB the 15% fraction takes over from the floor.
        val below = MemoryBudget.defaultCacheBytes(1 * gb)
        val above = MemoryBudget.defaultCacheBytes(2 * gb)
        assertEquals(256L * 1024 * 1024, below)
        assertTrue(above > below)
    }

    @Test fun `page bytes use Long math, no int overflow on huge pages`() {
        assertEquals(2400L * 3600 * 4, MemoryBudget.bytesForPage(2400, 3600))
        assertEquals(12000L * 12000 * 4, MemoryBudget.bytesForPage(12000, 12000))
    }

    @Test fun `tiny pages never report zero residency on a real budget`() {
        val budget = MemoryBudget.defaultCacheBytes(8 * gb)
        assertTrue(MemoryBudget.pagesResident(budget, 100, 100) > 1000)
    }
}
