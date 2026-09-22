package com.absolutex.remote.cloud

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A download URL that must re-resolve on a timer without stampeding the metadata endpoint. */
class CachedDownloadUrlTest {

    private var now = 0L
    private val resolves = AtomicInteger(0)

    private fun cached(ttl: Long = TTL, resolve: () -> String = { "url-${resolves.incrementAndGet()}" }) =
        CachedDownloadUrl("url-0", ttl, { now }, resolve)

    @Test fun `a fresh url is reused rather than re-resolved`() {
        val url = cached()
        repeat(BLOCKS) { assertEquals("url-0", url.get()) }
        // The point of caching: the transport asks per request, so re-resolving each time would
        // add a Graph round trip per block — hundreds for one book.
        assertEquals("nothing may be resolved while the url is fresh", 0, resolves.get())
    }

    @Test fun `the url is re-resolved once the ttl has passed`() {
        val url = cached()
        assertEquals("url-0", url.get())
        now += TTL
        assertEquals("url-1", url.get())
        assertEquals(1, resolves.get())
    }

    @Test fun `the ttl restarts from the new url, not from the first`() {
        val url = cached()
        now += TTL
        assertEquals("url-1", url.get())
        now += TTL - 1
        assertEquals("the second url must stay fresh for a full ttl", "url-1", url.get())
        assertEquals(1, resolves.get())
    }

    @Test fun `an expiry exactly on the boundary re-resolves rather than serving a stale url`() {
        // freshUntil is exclusive: at the instant it is reached the url is already suspect, and
        // the cheap answer (one metadata call) beats the expensive one (a wrong sign-in prompt).
        val url = cached()
        now += TTL
        url.get()
        assertEquals(1, resolves.get())
    }

    @Test fun `concurrent callers past the ttl resolve once, not once each`() {
        // readAt is explicitly concurrent, so an expired url expires for every in-flight read at
        // the same moment. Without single flight that is one metadata call per reader.
        val slow = cached(resolve = {
            Thread.sleep(SLOW_MS)
            "url-${resolves.incrementAndGet()}"
        })
        slow.get()
        now += TTL
        val barrier = CyclicBarrier(THREADS)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val work = (0 until THREADS).map {
                Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    slow.get()
                }
            }
            val urls = pool.invokeAll(work).map { it.get() }
            assertEquals("stampeded the metadata endpoint", 1, resolves.get())
            assertEquals("every caller must get the winner's url", setOf("url-1"), urls.toSet())
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val TTL = 120_000L
        const val BLOCKS = 300
        const val THREADS = 8
        const val SLOW_MS = 50L
    }
}
