package com.absolutex.core.decode

import com.absolutex.model.PageLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        /** Pages whose decode reports OutOfMemory instead of completing. */
        val oomPages = mutableSetOf<Int>()
        /** Pages that park non-cancellably and THEN report OutOfMemory when released. */
        val parkThenOomPages = mutableSetOf<Int>()
        /** Pages whose decode reports Unreadable instead of completing. */
        val unreadablePages = mutableSetOf<Int>()
        /** Pages that park NON-cancellably, like a blocking native decodeRegion. */
        val nonCancellablePages = mutableSetOf<Int>()

        suspend fun decode(page: Int): DecodeOutcome {
            started += page
            val early: DecodeOutcome? = when {
                page in oomPages -> DecodeOutcome.OutOfMemory
                page in unreadablePages -> DecodeOutcome.Unreadable
                autoComplete -> DecodeOutcome.Decoded(FAKE_IMAGE)
                else -> null
            }
            if (early != null) return early
            return parkAndDecode(page)
        }

        /**
         * Parks on the page's gate, then reports Decoded. The non-cancellable variant
         * models the real decoder: a blocking native call does not observe the job's
         * cancellation until it returns, so withContext(NonCancellable) keeps the gate
         * await running to completion even under cancel().
         */
        private suspend fun parkAndDecode(page: Int): DecodeOutcome {
            val gate = gates.getOrPut(page) { CompletableDeferred() }
            if (page in nonCancellablePages || page in parkThenOomPages) {
                withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                return if (page in parkThenOomPages) {
                    DecodeOutcome.OutOfMemory
                } else {
                    DecodeOutcome.Decoded(FAKE_IMAGE)
                }
            }
            try {
                gate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += page
                throw e
            }
            return DecodeOutcome.Decoded(FAKE_IMAGE)
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
        decodeDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): Harness {
        val decode = FakeDecode()
        val budget = java.util.concurrent.atomic.AtomicLong(budgetBytes)
        val tile = java.util.concurrent.atomic.AtomicLong(0)
        val evicted = mutableListOf<Int>()
        val engine = PrefetchEngine(
            scope = this,
            decodeDispatcher = decodeDispatcher,
            budgetBytes = { budget.get() },
            tileBytes = { tile.get() },
            pageBytesEstimate = { pageBytes },
            onDecoded = { _, est, _ -> est },
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
            val tiles = h.tileBytes.get()
            val resident = h.engine.residentBytes
            assertTrue(
                "budget blown at page $page: tiles=$tiles resident=$resident budget=${h.budget.get()}",
                tiles + resident <= h.budget.get(),
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

    @Test
    fun `an OOM decode sheds, stops the batch, and leaves the page unmarked`() = runTest {
        val h = harness()
        h.decode.oomPages += 12
        var shed = 0
        h.engine.onOutOfMemory = { shed++ }
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle()
        // 11 decoded fine; 12 OOMed and the batch stopped — 13 never starts.
        assertEquals(listOf(11, 12), h.decode.started)
        assertEquals(1, shed)
        // Everything resident was dropped: the budget is restored.
        assertEquals(0, h.engine.residentBytes)
        // The stopped batch stays stopped: a same-page settle is a no-op, and even a new
        // layout hint through onBudgetChanged plans nothing while stopped.
        h.engine.onBudgetChanged()
        assertTrue(h.decode.started.size == 2)
        // A new settle is a new batch: pressure may have passed, so the window replans.
        // Depth 2 from page 11 is {12, 13}: 12 now decodes (the OOM never marked it),
        // 13 starts fresh — it never ran in the stopped batch.
        h.decode.oomPages.clear()
        h.engine.onSettled(11, 200, PageLayout.SINGLE, depth = 2)
        advanceUntilIdle()
        assertEquals(listOf(11, 12, 12, 13), h.decode.started)
    }

    @Test
    fun `an unreadable prefetch decode is done-not-resident, not a failure`() = runTest {
        val h = harness()
        h.decode.unreadablePages += 12
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle()
        // 12 was attempted, reported unreadable, and the batch carried on to 13.
        assertEquals(listOf(11, 12, 13), h.decode.started)
        // Only the two decodable pages are resident; 12 holds no bytes.
        assertEquals(2 * 100_000, h.engine.residentBytes)
    }

    @Test
    fun `cancelInFlight waits for the gated decode to stop before the caller closes`() = runTest {
        // The recycle-under-decode race: evictFarPages closes a far page's PageImage on
        // Main while a decodeRegion for that page is still running on the decode pool —
        // the close blocks on the decoder's native lock until the decode finishes. The
        // contract under test: cancelInFlight(page) returns only after the decode has
        // actually stopped, so the close that follows runs against a quiet decoder.
        val h = harness()
        h.decode.autoComplete = false
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle() // 11, 12, 13 started, parked on their gates

        var closeAfterCancel = false
        val closer = launch {
            h.engine.cancelInFlight(12)
            // If cancelInFlight returned while the decode still held the gate, this
            // would race the decode's cancellation instead of ordering after it.
            closeAfterCancel = 12 in h.decode.cancelled
        }
        advanceUntilIdle()
        // Under Unconfined the cancellation is delivered synchronously, so the closer may
        // already be done — what must hold is the ORDER: the decode recorded its
        // cancellation before cancelInFlight returned, which closeAfterCancel asserts.
        // Release the gate in case the decode is still parked (a queueing dispatcher
        // would leave it so until cancellation is processed).
        h.decode.gates[12]?.complete(Unit)
        advanceUntilIdle()
        assertTrue(closer.isCompleted)
        assertTrue("close must observe the decode as already cancelled", closeAfterCancel)
        // The other gated decodes are untouched — cancelInFlight is per-page.
        assertTrue(11 !in h.decode.cancelled)
        assertTrue(13 !in h.decode.cancelled)
        h.engine.dropAll()
        advanceUntilIdle()
    }

    @Test
    fun `cancelInFlight suspends until the decode stops, on a queueing dispatcher`() = runTest {
        // This is the suspension half of the contract, against a decode that models the
        // real one: a blocking native decodeRegion does not observe cancellation until it
        // returns, so the join in cancelInFlight must hold the closer until the gate opens.
        val queued = StandardTestDispatcher(testScheduler)
        val h = harness(decodeDispatcher = queued)
        h.decode.autoComplete = false
        h.decode.nonCancellablePages += 12
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle() // 11 and 13 parked cancellably, 12 parked non-cancellably

        var closeAfterCancel = false
        val closer = launch {
            h.engine.cancelInFlight(12)
            closeAfterCancel = 12 in h.decode.cancelled
        }
        // The decode ignores the cancel until its gate opens, so the join holds the
        // closer across a full drain.
        advanceUntilIdle()
        assertTrue("closer must be suspended until the decode stops", !closer.isCompleted)
        // The native call returns; the job completes; only now does the join release.
        h.decode.gates.getValue(12).complete(Unit)
        advanceUntilIdle()
        assertTrue(closer.isCompleted)
        h.engine.dropAll()
        advanceUntilIdle()
    }

    @Test
    fun `a decode held across a book switch does not land into the new book`() = runTest {
        // The lead's finding (b): dropAll() at book open cancels in-flight decodes, but a
        // NON-CANCELLABLE decode (a blocking native call, per the fake above) outlives the
        // drop and its completion then writes into the engine's book-agnostic resident map.
        // Book B must not be billed for book A's page. If the engine has no way to express
        // "which book", the difficulty of this test IS the finding.
        val queued = StandardTestDispatcher(testScheduler)
        val h = harness(decodeDispatcher = queued)
        h.decode.autoComplete = false
        h.decode.nonCancellablePages += 11 // book A's page, mid native call

        // Book A: settle, decode 11 parks non-cancellably.
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 1)
        advanceUntilIdle()

        // Book B opens: dropAll (as ReaderViewModel.open does), then B reads page 10.
        h.engine.dropAll()
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 1)
        advanceUntilIdle()

        // Book A's decode finally returns from its native call.
        h.decode.gates.getValue(11).complete(Unit)
        advanceUntilIdle()

        // Book B's resident map must not contain page 11 — B never planned it — and B's
        // budget must not be billed for it.
        assertEquals(
            "book A's late decode must not land into book B's resident set",
            0,
            h.engine.residentBytes,
        )
        h.engine.dropAll()
        advanceUntilIdle()
    }

    @Test
    fun `a stale OOM does not stop the new book's batch`() = runTest {
        // The other half of the generation guard: book A's decode reporting OutOfMemory
        // AFTER book B opened must not shed B's fresh batch — B would start degraded for
        // a resource event that happened in a book the user already closed. Found by
        // reasoning during the guard's fix, so this test pins it: guarded-but-unpinned
        // survives one refactor and dies in the next.
        val queued = StandardTestDispatcher(testScheduler)
        val h = harness(decodeDispatcher = queued)
        h.decode.autoComplete = false
        h.decode.parkThenOomPages += 11 // book A's page: parks, then reports OOM on release

        // Book A: settle, decode 11 parks non-cancellably.
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 1)
        advanceUntilIdle()

        // Book B opens: dropAll, then B settles and plans its own window (page 11 again,
        // same index — the engine is book-agnostic, which is exactly why the guard needs
        // to be a generation and not a page key).
        var shed = 0
        h.engine.onOutOfMemory = { shed++ }
        h.engine.dropAll()
        h.engine.onSettled(10, 200, PageLayout.SINGLE, depth = 1)
        advanceUntilIdle()
        assertTrue("book B's own decode must be planned", 11 in h.decode.started)

        // Book A's decode returns from its native call — reporting OOM.
        h.decode.gates.getValue(11).complete(Unit)
        advanceUntilIdle()

        // The stale OOM must not have stopped B's batch or fired the host shed.
        assertEquals("a stale OOM must not fire the host shed", 0, shed)
        // And B's batch still plans: a further settle decodes ahead normally.
        h.engine.onSettled(11, 200, PageLayout.SINGLE, depth = 1)
        advanceUntilIdle()
        assertTrue(12 in h.decode.started)
        h.engine.dropAll()
        advanceUntilIdle()
    }

    @Test
    fun `the production lambda turns a thrown OutOfMemoryError into OutOfMemory, not Unreadable`() = runTest {
        // CRITICAL wiring test. pageImage's catch block does NOT catch Error
        // (OutOfMemoryError extends Error, not RuntimeException), so a real allocation
        // failure reaches the engine's decode lambda as a throw. The production lambda is
        // now `DecodeClassifier.classify { pageImage(page) }`. This test drives a REAL
        // throw through that exact wrapper — not FakeDecode's direct OutOfMemory return —
        // and asserts the engine's OOM path fires: batch stopped, host shed invoked,
        // budget restored. Before the fix this was `when (val image = pageImage(page))`
        // which let the throw propagate uncaught; the whole OOM path was unreachable.
        val queued = StandardTestDispatcher(testScheduler)
        val h = harness(decodeDispatcher = queued)
        h.engine.decode = { DecodeClassifier.classify { throw OutOfMemoryError("synthetic") } }
        var shed = 0
        h.engine.onOutOfMemory = { shed++ }
        h.engine.onSettled(0, 200, PageLayout.SINGLE, depth = 3)
        advanceUntilIdle()
        assertEquals(
            "a thrown OutOfMemoryError must be classified as OutOfMemory and fire the host shed",
            1, shed,
        )
        // The OOM path drops everything: the budget is restored. (batchStopped is the
        // internal flag; the observable contract is that the host shed fired and the
        // resident set was cleared — the stopped batch lasts only until the next settle.)
        assertEquals("the OOM path must clear the resident set", 0, h.engine.residentBytes)
        h.engine.dropAll()
        advanceUntilIdle()
    }
}

/** A stand-in image the fake decode hands back; the engine only forwards it to onDecoded. */
private val FAKE_IMAGE: PageImage = object : PageImage {
    override val width = 1
    override val height = 1
    override fun decodeBase(targetWidth: Int, targetHeight: Int): android.graphics.Bitmap =
        throw UnsupportedOperationException("not used in engine tests")
    override fun decodeTile(tile: Tile): android.graphics.Bitmap? = null
    override fun decodeThumbnail(targetEdge: Int): android.graphics.Bitmap? = null
    override fun close() {}
}
