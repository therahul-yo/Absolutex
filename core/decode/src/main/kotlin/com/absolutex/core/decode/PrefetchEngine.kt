package com.absolutex.core.decode

import com.absolutex.model.PageLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** One prefetched page: its book index, the job decoding it, and the bytes it estimated at. */
private class PrefetchEntry(val page: Int, val job: Job, val estimatedBytes: Long)

/**
 * Decodes pages ahead of the reader inside ONE shared memory budget with the tile cache (§3).
 *
 * The engine owns no threads and no dispatcher: every decode runs as a child of the scope
 * given at construction (the ViewModel's scope in production, a TestScope in tests), on the
 * caller-chosen [decodeDispatcher]. Bookkeeping happens in [onSettled] and the decode
 * completions, both in that scope; [inFlight] is a ConcurrentHashMap only because a decode
 * completion can race a settle, and [resident] is guarded by its own monitor.
 *
 * Budget invariant: tileBytes + residentBytes <= budgetBytes at all times. Checked before a
 * decode is planned (against an estimate) and again when it lands (against the real cost);
 * an overshoot evicts prefetched pages farthest from the current page first. The engine
 * never trims the tile cache itself — the cache's owner does that (onTrimMemory, the live
 * cache-size setting), and the engine reacts through [onBudgetChanged].
 *
 * Reversal: a settle that moves against the current direction cancels every in-flight page
 * now behind the reader immediately — no orphaned decode finishing into a cache nobody will
 * read. Completed pages behind the reader are simply the first eviction candidates.
 *
 * Nothing here runs on Main (the caller's scope does the launching, the dispatcher does the
 * decoding), nothing allocates per frame, and nothing runs inside a draw lambda: the engine
 * is driven by page settles, which arrive at most once per fling landing.
 */
class PrefetchEngine(
    private val scope: CoroutineScope,
    private val decodeDispatcher: CoroutineDispatcher,
    /**
     * The shared budget, as a supplier: the tile cache resizes live from the user's
     * cache-size setting (#46), so the prefetch set must react to a shrink, not just to
     * onTrimMemory — a cached budget constant would keep prefetching into headroom that
     * no longer exists.
     */
    private val budgetBytes: () -> Long,
    /** Current tile-cache footprint; the engine reads it, never writes it. */
    private val tileBytes: () -> Long,
    /** Bytes one page is estimated at, before decode. */
    private val pageBytesEstimate: (page: Int) -> Long,
    /** Actual bytes a decoded page holds, once it lands. */
    private val onDecoded: (page: Int, estimated: Long, image: PageImage) -> Long,
    /** Decode one page, classifying the outcome at the boundary that sees the cause. */
    private val decode: suspend (page: Int) -> DecodeOutcome,
) {

    private val inFlight = ConcurrentHashMap<Int, PrefetchEntry>()
    private val resident = LinkedHashMap<Int, Long>()

    @Volatile
    private var settled = -1

    @Volatile
    private var direction = 0

    /** Bytes currently held by prefetched pages that have landed. */
    val residentBytes: Long
        get() = synchronized(resident) { resident.values.sum() }

    /** Fired when a prefetched page is dropped, so the host can free what it holds for it. */
    var onEvicted: (page: Int, bytes: Long) -> Unit = { _, _ -> }

    /**
     * Fired when a prefetch decode reports [DecodeOutcome.OutOfMemory]: the engine has
     * already dropped everything; the host sheds its own state (tile cache, base layers)
     * here. The batch is stopped — no further decodes launch until the next settle, so
     * prefetch never re-attempts the allocation that just failed.
     */
    var onOutOfMemory: () -> Unit = {}

    /** True while an OOM has stopped the batch; cleared by the next settle. */
    @Volatile
    private var batchStopped = false

    /** A settle: update direction, cancel behind-work, restore the budget, plan ahead. */
    fun onSettled(page: Int, pageCount: Int, layout: PageLayout, depth: Int) {
        if (page == settled) return
        direction = if (settled < 0) 0 else (page - settled).sign().takeIf { it != 0 } ?: direction
        settled = page
        batchStopped = false // a new settle is a new batch: pressure may have passed
        cancelBehind()
        reconcile(protect = -1)
        planWindow(page, pageCount, layout, depth)
    }

    /**
     * Restores the budget invariant right now — the supplier may have shrunk (a live
     * cache-size change, or onTrimMemory halving the tile budget) without a new decode to
     * trigger the post-landing path. Evicts farthest from the settled page first.
     */
    fun onBudgetChanged() {
        reconcile(protect = -1)
    }

    /** Cancels every in-flight page now behind the reader. */
    private fun cancelBehind() {
        val behind = PrefetchPlanner.behind(inFlight.keys.toHashSet(), settled, direction)
        for (page in behind) {
            inFlight.remove(page)?.job?.cancel()
        }
    }

    /**
     * Cancels one page's in-flight decode and suspends until it has actually stopped, so
     * the caller can safely close that page's [PageImage] afterwards. The ordering is the
     * point (see the recycle-under-decode race): BitmapRegionDecoder.close() synchronises
     * on the decoder's native lock, so closing while a decodeRegion for the same page is
     * still running makes the closing thread — Main, from evictFarPages — wait for the
     * decode to finish. Cancelling first and waiting for the stop means the close runs
     * against a quiet decoder: a frame hitch becomes a non-event.
     *
     * Must be called from the engine's own scope (it joins the job); returns immediately
     * for a page that is not in flight.
     */
    suspend fun cancelInFlight(page: Int) {
        val job = inFlight[page]?.job ?: return
        job.cancel()
        job.join()
    }

    private fun planWindow(page: Int, pageCount: Int, layout: PageLayout, depth: Int) {
        if (batchStopped) return
        val window = PrefetchPlanner.window(page, pageCount, layout, direction, depth)
        for (target in window) {
            if (batchStopped) return
            if (inFlight.containsKey(target) || resident.containsKey(target)) continue
            // Make room farthest-first, never dropping the settled page or the page about to
            // be decoded; if the budget still says no, skip the page until the next settle.
            while (tileBytes() + residentBytes + pageBytesEstimate(target) > budgetBytes()) {
                val order = evictCandidates(page, protect = target)
                if (order.isEmpty() || evictOne(order) <= 0) break
            }
            if (tileBytes() + residentBytes + pageBytesEstimate(target) > budgetBytes()) continue
            launchDecode(target)
        }
    }

    private fun launchDecode(target: Int) {
        val estimate = pageBytesEstimate(target)
        // LAZY start with register-then-run: with a synchronous dispatcher (Unconfined in
        // tests, or a decode that completes without suspending), the body can finish
        // inside scope.launch() BEFORE the inFlight put below would run — the finally's
        // remove would then be a no-op and the stale entry would block that page from
        // ever being prefetched again. Registering first and starting after makes the
        // ordering a guarantee instead of a race. Still launched straight into the scope:
        // a wrapping Job(parent) never completes on its own and hangs the job tree.
        val job = scope.launch(decodeDispatcher, start = CoroutineStart.LAZY) {
            try {
                when (val outcome = decode(target)) {
                    is DecodeOutcome.Decoded -> {
                        val actual = onDecoded(target, estimate, outcome.image)
                        synchronized(resident) { resident[target] = actual }
                        reconcile(protect = target)
                    }
                    DecodeOutcome.Unreadable ->
                        // A property of the page: done, not resident, no bytes. The
                        // caller's own failure mark (if any) is its business; prefetch
                        // never marks and never retries within the window.
                        Unit
                    DecodeOutcome.OutOfMemory -> {
                        // Resource event (docs/decode-oom-policy.md): shed everything,
                        // stop this batch, leave the page unmarked. The visible page's
                        // own path retries once with the budget actually freed.
                        batchStopped = true
                        dropAll()
                        onOutOfMemory()
                    }
                }
            } finally {
                inFlight.remove(target)
            }
        }
        inFlight[target] = PrefetchEntry(target, job, estimate)
        job.start()
    }

    /** Restores the budget invariant: evict farthest-first until tile + resident fits. */
    private fun reconcile(protect: Int) {
        var over = tileBytes() + residentBytes - budgetBytes()
        while (over > 0) {
            val order = evictCandidates(settled, protect)
            if (order.isEmpty()) break
            val freed = evictOne(order)
            if (freed <= 0) break
            over -= freed
        }
    }

    /** Eviction candidates, farthest from [page] first, never the protected pages. */
    private fun evictCandidates(page: Int, protect: Int): List<Int> =
        PrefetchPlanner.evictOrder(
            synchronized(resident) {
                resident.keys.filter { it != settled && it != protect }
            }.toSet(),
            page,
            direction,
        )

    /** Drops the first candidate and reports it through [onEvicted]; returns bytes freed. */
    private fun evictOne(order: List<Int>): Long {
        val victim = order.firstOrNull() ?: return 0
        val bytes = synchronized(resident) { resident.remove(victim) } ?: return 0
        onEvicted(victim, bytes)
        return bytes
    }

    /** Drops every prefetched page and cancels every in-flight decode. For book close/trim. */
    fun dropAll() {
        inFlight.values.forEach { it.job.cancel() }
        inFlight.clear()
        synchronized(resident) { resident.clear() }
    }

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}
