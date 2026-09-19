package com.absolutex.core.decode

import com.absolutex.model.PageLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrefetchEngine behaviour, driven entirely by a TestScope and a fake decoder whose decodes
 * gate on CompletableDeferreds — no real clock, no Bitmaps (no Robolectric in core/decode by
 * design). Asserts decode counts for a scroll pattern, prompt reversal cancellation, the
 * shared-budget invariant across a synthetic 200-page book, and both book ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchEngineTest {

    /** A fake decode that records starts, and can be held at a page or released at will. */
    private class FakeDecode {
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var autoComplete = true

        suspend fun decode(page: Int) {
            started += page
            if (autoComplete) return
            val gate = gates.getOrPut(page) { CompletableDeferred() }
            try {
                gate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += page
                throw e
            }
        }
    }

    private class Harness(
        val scope: TestScope,
        val decode: FakeDecode,
        val engine: PrefetchEngine,
        val budget: java.util.concurrent.atomic.AtomicLong,
        val tileBytes: java.util.concurrent.atomic.AtomicLong,
        val evicted: MutableList<Int>,
    )

    private fun TestScope.harness(
        budgetBytes: Long = 1_000_000,
        pageBytes: Long = 100_000,
    ): Harness {
        val decode = FakeDecode()
        val budget = java.util.concurrent.atomic.AtomicLong(budgetBytes)
        val tile = java.util.concurrent.atomic.AtomicLong(0)
        val evicted = mutableListOf<Int>()
        val engine = PrefetchEngine(
            scope = this,
            decodeDispatcher = Dispatchers.Unconfined,
            budgetBytes = { budget.get() },
            tileBytes = { tile.get() },
            pageBytesEstimate = { pageBytes },
            onDecoded = { _, est -> est },
            decode = { decode.decode(it) },
        )
        engine.onEvicted = { page, _ -> evicted += page }
        return Harness(this, decode, engine, budget, tile, evicted)
    }

    @Test
    fun `a settle forward decodes the window ahead once`() = runTest {
        val h = harness()
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle()
        assertEquals(listOf(11, 12, 13), h.decode.started)
    }

    @Test
    fun `a settle on the same page plans nothing new`() = runTest {
        val h = harness()
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle()
        h.decode.started.clear()
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle()
        assertTrue(h.decode.started.isEmpty())
    }

    @Test
    fun `reversal cancels in-flight pages now behind, promptly`() = runTest {
        val h = harness()
        h.decode.autoComplete = false
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle() // 11, 12, 13 started, all parked on their gates
        assertEquals(listOf(11, 12, 13), h.decode.started)
        // Reader turns back: 11-13 are now behind. The cancel must land without any gate
        // being released — that is the "no orphaned decode finishing into a cache nobody
        // will read" requirement.
        h.engine.onSettled(9, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle()
        assertEquals(setOf(11, 12, 13), h.decode.cancelled.toSet())
        // The new window plans backward from 9: 8, 7, 6.
        assertEquals(listOf(11, 12, 13, 8, 7, 6), h.decode.started)
        // The new window's decodes park on their gates; drop them so the test scope
        // completes (backgroundScope would also work, but the engine is the thing under
        // test and dropAll is part of its contract).
        h.engine.dropAll()
        advanceUntilIdle()
    }

    @Test
    fun `budget is never exceeded across a synthetic 200-page book`() = runTest {
        // Budget 450k, page 100k, tiles grow with every settle: 4 pages fit beside the
        // tiles at the start, fewer as tiles grow. Walk the whole book settling every page.
        val h = harness(budgetBytes = 450_000, pageBytes = 100_000)
        for (page in 0 until 200) {
            h.tileBytes.set((page * 1_000L).coerceAtMost(200_000))
            h.engine.onSettled(page, 200, PageLayout.SINGLE, depth = 5)
            advanceUntilIdle()
            assertTrue(
                "budget blown at page $page: tiles=${h.tileBytes.get()} resident=${h.engine.residentBytes} budget=${h.budget.get()}",
                h.tileBytes.get() + h.engine.residentBytes <= h.budget.get(),
            )
        }
    }

    @Test
    fun `a budget shrink evicts down to the new ceiling without a decode landing`() = runTest {
        val h = harness(budgetBytes = 1_000_000, pageBytes = 100_000)
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle()
        assertEquals(3 * 100_000, h.engine.residentBytes)
        // The user shrinks the cache mid-book (live resize, #46): tiles claim 700k of the
        // new 900k budget, so the prefetch set is over by exactly 100k — one page.
        h.tileBytes.set(700_000)
        h.budget.set(900_000)
        h.engine.onBudgetChanged()
        assertTrue(
            "tiles=${h.tileBytes.get()} resident=${h.engine.residentBytes} budget=${h.budget.get()}",
            h.tileBytes.get() + h.engine.residentBytes <= h.budget.get(),
        )
        // Eviction is farthest-first: 13 goes, and that alone restores the invariant.
        assertEquals(listOf(13), h.evicted)
    }

    @Test
    fun `settling at the last page plans nothing forward`() = runTest {
        val h = harness()
        h.engine.onSettled(199, 200, PageLayout.SINGLE, depth = 4)
        advanceUntilIdle()
        assertTrue(h.decode.started.isEmpty())
    }

    @Test
    fun `settling at page zero plans nothing backward`() = runTest {
        val h = harness()
        h.engine.onSettled(0, 200, PageLayout.SINGLE, depth = 4)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3, 4), h.decode.started)
        h.decode.started.clear()
        // A reversal at the book's start: nothing behind page zero to plan or cancel.
        h.engine.onSettled(0, 200, PageLayout.SINGLE, depth = 4)
        advanceUntilIdle()
        assertTrue(h.decode.started.isEmpty())
    }

    @Test
    fun `dropAll cancels in-flight and clears resident`() = runTest {
        val h = harness()
        h.decode.autoComplete = false
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle()
        h.engine.dropAll()
        advanceUntilIdle()
        assertEquals(setOf(11, 12, 13), h.decode.cancelled.toSet())
        assertEquals(0, h.engine.residentBytes)
    }
}
