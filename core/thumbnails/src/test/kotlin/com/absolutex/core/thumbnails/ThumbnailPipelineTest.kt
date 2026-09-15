package com.absolutex.core.thumbnails

import com.absolutex.model.Page
import com.absolutex.source.ComicSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/**
 * Pipeline behavior runs on the JVM here through Robolectric (Bitmaps) plus kotlinx-coroutines-test
 * (structured batch and cancellation tests). No test asserts on wall-clock time: rendezvous use
 * latches, and budgets use byte counts, entry counts and proportions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbnailPipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun pipeline(dir: String = "thumbs") =
        ThumbnailPipeline(File(tmp.root, dir), 4L * 1024 * 1024)

    private fun request(page: Int) = ThumbRequest("book-a", page, ThumbRequest.BUCKET_SMALL)

    private fun truncated(): ByteArray {
        // A cut-off PNG fails both the device decoder and the Robolectric shadow with an IOException.
        val png = testPngBytes(64, 32)
        return png.copyOf(png.size / 2)
    }

    @Test fun `a memory hit serves the same bitmap without re-reading the source`() = runTest {
        val source = FakeSource(mapOf(0 to testPngBytes(64, 32)))
        val thumbs = pipeline()
        val first = thumbs.load(source, request(0))
        val second = thumbs.load(source, request(0))
        assertSame(first, second)
        assertEquals(1, source.reads.get())
    }

    @Test fun `a disk hit serves a fresh pipeline without re-reading the source`() = runTest {
        val source = FakeSource(mapOf(0 to testPngBytes(64, 32)))
        pipeline().load(source, request(0))
        assertEquals(1, source.reads.get())
        val second = pipeline().load(source, request(0))
        assertEquals(1, source.reads.get())
        assertEquals(ThumbRequest.BUCKET_SMALL, second.width)
    }

    @Test(expected = IOException::class)
    fun `corrupt source bytes throw IOException`() = runTest {
        pipeline().load(FakeSource(mapOf(0 to truncated())), request(0))
    }

    @Test fun `poison is never cached so a retry re-reads the source`() = runTest {
        val source = FakeSource(mapOf(0 to truncated()))
        val thumbs = pipeline()
        runCatching { thumbs.load(source, request(0)) }
        runCatching { thumbs.load(source, request(0)) }
        assertEquals(2, source.reads.get())
    }

    @Test fun `a batch isolates failures per key`() = runTest {
        val source = FakeSource(mapOf(0 to testPngBytes(64, 32)))
        val results = pipeline().load(source, listOf(request(0), request(1)))
        assertEquals(2, results.size)
        assertTrue(results[request(0)]?.isSuccess == true)
        assertTrue(results[request(1)]?.exceptionOrNull() is IOException)
        assertEquals(ThumbRequest.BUCKET_SMALL, results[request(0)]?.getOrThrow()?.width)
    }

    @Test fun `a batch fans out within the concurrency bound`() = runTest {
        val blobs = (0 until 16).associateWith { testPngBytes(48, 24) }
        val source = RacingSource(blobs)
        val results = pipeline().load(source, blobs.keys.sorted().map { request(it) })
        assertEquals(16, results.size)
        assertTrue(results.values.all { it.isSuccess })
        assertEquals(16, source.reads.get())
        assertTrue(source.peak.get() <= ThumbnailPipeline.MAX_CONCURRENT_LOADS)
    }

    @Test fun `a cancelled load never populates the cache`() = runTest {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val source = FakeSource(mapOf(0 to testPngBytes(64, 32)), gate = gate, entered = entered)
        val thumbs = pipeline()
        val pending = async(Dispatchers.IO) { thumbs.load(source, request(0)) }
        // Rendezvous, not a timeout: the load parks in openPage before the cancel can land.
        withContext(Dispatchers.IO) { entered.await() }
        thumbs.cancel(request(0))
        gate.countDown()
        val outcome = runCatching { pending.await() }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        thumbs.load(source, request(0))
        assertEquals(2, source.reads.get())
    }

    @Test fun `cancelling idle keys never crashes and later loads work`() = runTest {
        val source = FakeSource(mapOf(0 to testPngBytes(64, 32)))
        val thumbs = pipeline()
        thumbs.cancel(request(0))
        thumbs.cancelAll()
        thumbs.load(source, request(0))
        thumbs.close()
        thumbs.cancel(request(0))
        assertEquals(1, source.reads.get())
    }

    @Test fun `an empty batch returns an empty map`() = runTest {
        val source = FakeSource(mapOf(0 to testPngBytes(64, 32)))
        assertTrue(pipeline().load(source, emptyList()).isEmpty())
        assertEquals(0, source.reads.get())
    }

    @Test fun `loads persist one disk entry per key`() = runTest {
        val source = FakeSource(mapOf(0 to testPngBytes(64, 32), 1 to testPngBytes(32, 64)))
        val thumbs = pipeline()
        thumbs.load(source, request(0))
        thumbs.load(source, request(1))
        val entries = File(tmp.root, "thumbs").listFiles()?.count { it.extension == "thumb" }
        assertEquals(2, entries)
    }

    private class FakeSource(
        private val blobs: Map<Int, ByteArray>,
        val reads: AtomicInteger = AtomicInteger(0),
        val gate: CountDownLatch? = null,
        val entered: CountDownLatch? = null,
    ) : ComicSource {
        override val pages: List<Page> = blobs.keys.sorted().map { Page(it, "page$it.png") }

        override fun openPage(index: Int): InputStream {
            reads.incrementAndGet()
            entered?.countDown()
            gate?.await()
            return blobs[index]?.inputStream() ?: throw IOException("No page $index")
        }

        override fun close() = Unit
    }

    private class RacingSource(private val blobs: Map<Int, ByteArray>) : ComicSource {
        val reads = AtomicInteger(0)
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)

        override val pages: List<Page> = blobs.keys.sorted().map { Page(it, "page$it.png") }

        override fun openPage(index: Int): InputStream {
            reads.incrementAndGet()
            val now = live.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                return blobs[index]?.inputStream() ?: throw IOException("No page $index")
            } finally {
                live.decrementAndGet()
            }
        }

        override fun close() = Unit
    }
}
