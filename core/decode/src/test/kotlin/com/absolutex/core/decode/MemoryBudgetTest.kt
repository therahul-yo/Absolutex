package com.absolutex.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetTest {

    private val gb = 1024L * 1024 * 1024

    @Test fun `reference device 8GB gets about 1_2 GB`() {
        val budget = MemoryBudget.defaultCacheBytes(8 * gb)
        assertEquals(1.2, budget.toDouble() / gb, 0.05)
    }

    @Test fun `16GB phone scales up, which is the point of the setting`() {
        assertTrue(MemoryBudget.defaultCacheBytes(16 * gb) > MemoryBudget.defaultCacheBytes(8 * gb))
    }

    @Test fun `budget is clamped at both ends`() {
        assertEquals(256L * 1024 * 1024, MemoryBudget.defaultCacheBytes(1 * gb))
        assertEquals(4 * gb, MemoryBudget.defaultCacheBytes(64 * gb))
    }

    @Test fun `whole chapter of real pages fits on the reference device`() {
        // Absolute Batman 001: 45 pages at 1988x3057.
        val budget = MemoryBudget.defaultCacheBytes(8 * gb)
        assertTrue(
            "expected the whole 45-page chapter resident",
            MemoryBudget.pagesResident(budget, 1988, 3057) >= 45,
        )
    }

    @Test fun `the brief's 2400x3600 benchmark book needs prefetch, not full residency`() {
        val budget = MemoryBudget.defaultCacheBytes(8 * gb)
        val resident = MemoryBudget.pagesResident(budget, 2400, 3600)
        assertTrue("expected well over the +-10 page prefetch floor", resident >= 20)
        assertTrue("180 pages should NOT all fit in 8GB - prefetch window applies", resident < 180)
    }
}
