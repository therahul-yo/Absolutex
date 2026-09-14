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
}
