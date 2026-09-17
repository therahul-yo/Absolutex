package com.absolutex.core.decode

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded pools sized to the big cores (§3). Deliberately NOT Dispatchers.Default or .IO:
 * Default is sized to all cores including the littles, and IO grows to 64 threads, which for
 * CPU-bound decode means context-switch churn and peak memory spikes from too many in-flight
 * bitmaps.
 */
object DecodeDispatchers {

    private fun pool(name: String, threads: Int): CoroutineDispatcher {
        val n = AtomicInteger(1)
        val factory = ThreadFactory { r ->
            Thread(r, "$name-${n.getAndIncrement()}").apply {
                priority = Thread.NORM_PRIORITY - 1   // never outrank the UI thread
            }
        }
        return Executors.newFixedThreadPool(threads, factory).asCoroutineDispatcher()
    }

    /** Page decode. Owns the big cores. */
    val decode: CoroutineDispatcher by lazy { pool("decode", CpuTopology.bigCoreCount) }

    /**
     * Archive entry extraction. Sized one above decode so extraction keeps the decode pool fed
     * while a page is in flight, but stays bounded — UFS 3.1 makes parallel reads cheap (§1).
     */
    val extract: CoroutineDispatcher by lazy { pool("extract", CpuTopology.bigCoreCount + 1) }

    /** Thumbnails. Kept small and separate so it can never starve the reader (§3). */
    val thumbnail: CoroutineDispatcher by lazy {
        pool("thumb", (CpuTopology.bigCoreCount / 2).coerceAtLeast(1))
    }

    /**
     * PDF page export. Its own single thread, never [extract]'s: a full-resolution export render
     * can run for hundreds of milliseconds, and sharing a pool with archive extraction would cost
     * the reader one of [extract]'s few threads for that whole span, mid-book, for a page nobody
     * is currently reading.
     *
     * ponytail: PDFium's own process-wide render lock (see PdfDocument's doc) still serialises
     * this against a concurrent pan/zoom decode at the native level — moving dispatcher only
     * stops Kotlin's scheduler from compounding that with a *thread-pool* stall too. Tiling the
     * export render the way the interactive path already tiles pages would remove the native
     * contention as well, if a large export ever measures as worse than this.
     */
    val export: CoroutineDispatcher by lazy { pool("export", 1) }
}
