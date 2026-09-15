package com.absolutex.core.thumbnails

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The thumbnail pipeline's own bounded dispatcher.
 *
 * Sized at half the cores and kept off every reader pool: thumbnails must never starve the reader.
 */
// TODO(thumbs): adopt DecodeDispatchers.thumbnail once the lead exposes it;
// core/decode is lead-owned, so this lane defines its own dispatcher and notes it.
object ThumbnailDispatchers {

    val thumbnails: CoroutineDispatcher by lazy {
        val total = Runtime.getRuntime().availableProcessors()
        val threads = (total / THREAD_DIVISOR).coerceAtLeast(MIN_THREADS)
        val count = AtomicInteger(1)
        val factory = ThreadFactory { work ->
            Thread(work, "$THREAD_PREFIX${count.getAndIncrement()}").apply {
                priority = Thread.NORM_PRIORITY - THREAD_PRIORITY_ADJUST
                isDaemon = true
            }
        }
        Executors.newFixedThreadPool(threads, factory).asCoroutineDispatcher()
    }

    private const val THREAD_DIVISOR = 2
    private const val MIN_THREADS = 1
    private const val THREAD_PRIORITY_ADJUST = 1
    private const val THREAD_PREFIX = "thumb-"
}
