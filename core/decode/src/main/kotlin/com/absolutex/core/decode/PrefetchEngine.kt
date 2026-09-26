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
    /**
     * Optional: fetch a CONTIGUOUS run of pages' bytes in one archive walk, before any of
     * them decode. Null means the host has no batch reader — a folder, a remote source, a
     * test — and every page then decodes on its own exactly as before.
     *
     * This is a prefetch of *bytes*, not of decoded pages: the pages still decode in
     * parallel on [decodeDispatcher] afterwards. It exists because a per-ordinal archive read
     * re-walks every entry header from zero, so a window of N pages read individually costs N
     * walks — 61.2 ms against 45.8 ms for a 10-page window at page 151 of the 300-page 6 MP
     * corpus (tools/bench-decode.sh).
     *
     * Must return a map parallel to the requested pages, or null if it could not serve the
     * run. The engine treats null as "no prefetch" and falls back, so a source that cannot
     * batch is never a broken one.
     */
    private val prefetchWindow: (suspend (pages: List<Int>) -> Map<Int, ByteArray?>?)? = null,
    /** Decode one page, classifying the outcome at the boundary that sees the cause. */
    internal var decode: suspend (page: Int) -> DecodeOutcome,
) {

    private val inFlight = ConcurrentHashMap<Int, PrefetchEntry>()
    private val resident = LinkedHashMap<Int, Long>()

    /**
     * Compressed bytes fetched by one archive walk and waiting for their decode to claim
     * them. Bounded by construction: [stageBytes] writes only the current window's pages and
     * only when every requested page came back, and [takeStagedBytes] removes on claim, so
     * nothing here outlives the decode that wanted it.
     */
    private val stagedBytes = mutableMapOf<Int, ByteArray>()

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

    /**
     * Book generation, bumped by [dropAll]. A decode that completes for a stale generation
     * writes nothing: a non-cancellable decode (a blocking native call) can outlive the
     * dropAll that a book switch performs, and its landing must not bill the NEW book's
     * resident set for the OLD book's page. Without this, ordering is the only protection
     * and ordering is not a guarantee — proven by the book-switch test.
     */
    @Volatile
    private var generation = 0

    /** A settle: update direction, cancel behind-work, restore the budget, plan ahead. */
    fun onSettled(page: Int, pageCount: Int, layout: PageLayout, depth: Int) {
        if (page == settled) return
        val step = (page - settled).let { kotlin.math.sign(it.toFloat()).toInt() }
        direction = if (settled < 0) 0 else step.takeIf { it != 0 } ?: direction
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
        val targets = window.filter { !inFlight.containsKey(it) && !resident.containsKey(it) }
        if (targets.isEmpty()) return
        // Bytes first, then plan: the whole point is that one archive walk serves several
        // pages, and that has to happen BEFORE the individual decodes are planned. Doing it
        // after would leave the reads exactly as N walks.
        scope.launch(decodeDispatcher) { stageBytes(targets) }
        for (target in targets) {
            if (batchStopped) return
            planOne(target, page)
        }
    }

    /**
     * Reads a contiguous run of the window in one archive walk, for [decode] to find.
     *
     * Best-effort throughout, and never a failure: a null return, a short map, or a source
     * with no batch reader all leave [decode] reading the page itself, which is the
     * pre-existing behaviour. The optimisation must not be able to fail a page.
     */
    private suspend fun stageBytes(targets: List<Int>) {
        val batch = prefetchWindow ?: return
        val first = targets.first()
        // Only a genuinely consecutive run is worth one walk. PrefetchPlanner returns a
        // window that walks off one end of the book, so the tail is not contiguous.
        val contiguous = targets.withIndex().all { (i, target) -> target == first + i }
        if (!contiguous) return
        val staged = runCatching { batch(targets) }.getOrNull() ?: return
        if (staged.size != targets.size) return
        // Bounded: only the pages this window asked for, and only the ones still wanted.
        // A page evicted between planning and staging must not be staged into.
        synchronized(stagedBytes) {
            targets.forEach { stagedBytes.remove(it) }
            staged.forEach { (page, bytes) -> if (bytes != null) stagedBytes[page] = bytes }
        }
    }

    /**
     * Bytes staged by [stageBytes] for [page], or null.
     *
     * Consumed once: the first decode takes the bytes and drops them, so a page cannot be
     * held by the staging map after the image that owns it exists. That is what keeps this
     * from becoming a second unbounded retention path alongside the resident set — the
     * compressed bytes here are the same ones the resident set would hold, and holding both
     * for the same page is exactly the duplication the budget exists to prevent.
     *
     * Public because the HOST owns the decode and must be the one to claim: the engine
     * never sees a page's bytes, only its decoded image, so it cannot claim on the host's
     * behalf. Null is the ordinary answer for a page outside the window.
     */
    fun takeStagedBytes(page: Int): ByteArray? = synchronized(stagedBytes) {
        stagedBytes.remove(page)
    }

    /**
     * Pages staged but not yet claimed.
     *
     * Public for the same reason [takeStagedBytes] is: the host is the claimant, so an
     * assertion that nothing leaked has to be able to ask. A test that could only observe
     * "did the batch get called" would pass even with a staging map that never drained,
     * which is precisely the leak this number exists to rule out.
     */
    val stagedPageCount: Int get() = synchronized(stagedBytes) { stagedBytes.size }

    /** Plans a single target: makes room, checks budget, launches. */
    private fun planOne(target: Int, page: Int) {
        // Make room farthest-first, never dropping the settled page or the page about to
        // be decoded; if the budget still says no, skip the page until the next settle.
        while (tileBytes() + residentBytes + pageBytesEstimate(target) > budgetBytes()) {
            val order = evictCandidates(page, protect = target)
            if (order.isEmpty() || evictOne(order) <= 0) break
        }
        if (tileBytes() + residentBytes + pageBytesEstimate(target) > budgetBytes()) return
        launchDecode(target)
    }

    private fun launchDecode(target: Int) {
        val estimate = pageBytesEstimate(target)
        // Capture the book generation at launch: this decode belongs to it, and a landing
        // after dropAll() (a book switch) must not bill the new book.
        val launchedGeneration = generation
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
                        if (launchedGeneration == generation) {
                            val actual = onDecoded(target, estimate, outcome.image)
                            synchronized(resident) { resident[target] = actual }
                            reconcile(protect = target)
                        }
                        // A stale landing writes nothing: the decode started for a book
                        // that has since closed, and the image it produced is the host's
                        // to manage through its own book-switch path.
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
                        // Guarded on generation: a stale OOM (book A's decode reporting
                        // after book B opened) must not stop B's fresh batch.
                        if (launchedGeneration == generation) {
                            batchStopped = true
                            dropAll()
                            onOutOfMemory()
                        }
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
        var canEvict = true
        while (over > 0 && canEvict) {
            val order = evictCandidates(settled, protect)
            val freed = if (order.isEmpty()) -1 else evictOne(order)
            if (freed <= 0) canEvict = false else over -= freed
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
        // Invalidate every in-flight decode: a non-cancellable one can outlive this call,
        // and its landing must not write into the next book's resident set.
        generation++
        inFlight.values.forEach { it.job.cancel() }
        inFlight.clear()
        synchronized(resident) { resident.clear() }
        // Staged bytes belong to the book being dropped. A decode that claims one of them
        // after a book switch would decode the OLD book's page and bill the NEW book for
        // it -- the same mistake the generation guard exists to prevent, in a field the
        // guard does not cover.
        synchronized(stagedBytes) { stagedBytes.clear() }
    }

}
