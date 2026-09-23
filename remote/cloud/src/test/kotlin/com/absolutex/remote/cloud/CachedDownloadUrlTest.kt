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

    // --- a url refused before its ttl ---------------------------------------------------

    @Test fun `a url the server refused is replaced rather than handed back again`() {
        // The timer cannot catch revocation or a lifetime Graph shortened on its own. Since the
        // download url is fetched with no Authorization header, a refusal on it cannot mean the
        // account was rejected — there is no account credential on that request — so it can only
        // mean the url is dead, and re-resolving is the whole remedy.
        val url = cached()
        assertEquals("url-0", url.get())
        assertEquals("url-1", url.get(refused = "url-0"))
        assertEquals(1, resolves.get())
    }

    @Test fun `a refusal naming a url already replaced resolves nothing`() {
        // The compare-and-swap. Readers fan out, so one dead url is refused N times at once; a
        // loser arriving after the winner has already replaced it must take the winner's url,
        // not throw it away and fetch an N+1th.
        val url = cached()
        assertEquals("url-1", url.get(refused = "url-0"))
        assertEquals("url-1", url.get(refused = "url-0"))
        assertEquals("a late loser must not re-resolve what a winner already replaced", 1, resolves.get())
    }

    @Test fun `a refusal restarts the ttl from the replacement`() {
        val url = cached()
        url.get(refused = "url-0")
        now += TTL - 1
        assertEquals("the replacement must get a full ttl of its own", "url-1", url.get())
        assertEquals(1, resolves.get())
    }

    @Test fun `an ordinary ask names no url and so cannot force a resolve`() {
        // get() and get(null) must be the same question. If a null refusal matched the held url
        // the cache would re-resolve on every single request, which is the cost caching exists
        // to avoid — about one Graph round trip per block of a book.
        val url = cached()
        repeat(BLOCKS) { assertEquals("url-0", url.get(refused = null)) }
        assertEquals(0, resolves.get())
    }

    @Test fun `concurrent refusals of the same url resolve once, not once each`() {
        val slow = cached(resolve = {
            Thread.sleep(SLOW_MS)
            "url-${resolves.incrementAndGet()}"
        })
        val barrier = CyclicBarrier(THREADS)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val work = (0 until THREADS).map {
                Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    slow.get(refused = "url-0")
                }
            }
            val urls = pool.invokeAll(work).map { it.get() }
            assertEquals("a fan-out of refusals stampeded the metadata endpoint", 1, resolves.get())
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
