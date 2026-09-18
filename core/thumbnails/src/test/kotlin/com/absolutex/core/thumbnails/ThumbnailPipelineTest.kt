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
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/**
 * Pipeline behavior runs on the JVM here through Robolectric (Bitmaps) plus kotlinx-coroutines-test
 * (structured batch and cancellation tests). No test asserts on wall-clock time: rendezvous use
 * latches, and budgets use byte counts, entry counts and proportions.
 *
 * Native graphics mode is required, not cosmetic: the legacy shadow's [android.graphics.Bitmap.compress]
 * writes PNG bytes for every [android.graphics.Bitmap.CompressFormat], which would make the WEBP
 * encode test pass even if the production code silently kept using PNG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test(expected = IOException::class)
    fun `empty book throws IOException, never IndexOutOfBounds`() = runTest {
        // Zero pages: without the guard this reaches openPage(0) and crashes. Local or
        // remote, every source routes through load(), so one test covers both paths.
        pipeline().load(FakeSource(emptyMap()), request(0))
    }

    @Test fun `immediate failure preserves IOException and allows retry`() = runTest {
        // Inline execution forces completion before computeIfAbsent returns, without timing guesses.
        val thumbs = ThumbnailPipeline(File(tmp.root, "inline"), dispatcher = Dispatchers.Unconfined)
        val source = FakeSource(emptyMap())
        try {
            repeat(2) {
                val failure = runCatching { thumbs.load(source, request(0)) }.exceptionOrNull()
                assertTrue("Expected IOException, got $failure", failure is IOException)
            }
            assertEquals(2, source.reads.get())
        } finally {
            thumbs.close()
        }
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

    @Test fun `disk entries are stored as WEBP, not PNG`() = runTest {
        // Lossless PNG was hundreds of KB per cover and slow to encode; WEBP_LOSSY replaces it.
        val source = FakeSource(mapOf(0 to testPngBytes(64, 32)))
        pipeline().load(source, request(0))
        val stored = File(tmp.root, "thumbs").listFiles()!!.first { it.extension == "thumb" }.readBytes()
        // A WEBP file is a RIFF container with the "WEBP" fourCC at offset 8; PNG starts 0x89 P N G.
        assertEquals("RIFF", String(stored, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(stored, 8, 4, Charsets.US_ASCII))
    }

    @Test fun `two concurrent loads of the same request hit the source once`() = runTest {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val source = FakeSource(mapOf(0 to testPngBytes(64, 32)), gate = gate, entered = entered)
        val thumbs = pipeline()
        val first = async(Dispatchers.IO) { thumbs.load(source, request(0)) }
        // Rendezvous: the first load is parked in openPage, so the second is guaranteed to find its
        // Deferred already registered and join it instead of starting a second decode.
        withContext(Dispatchers.IO) { entered.await() }
        val second = async(Dispatchers.IO) { thumbs.load(source, request(0)) }
        gate.countDown()
        val firstBitmap = first.await()
        val secondBitmap = second.await()
        assertSame(firstBitmap, secondBitmap)
        assertEquals(1, source.reads.get())
    }

    @Test fun `cancelling one request leaves a sibling load in the same caller coroutine alive`() = runTest {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val cancelledSource = FakeSource(mapOf(0 to testPngBytes(64, 32)), gate = gate, entered = entered)
        val siblingSource = FakeSource(mapOf(0 to testPngBytes(32, 16)))
        val thumbs = pipeline()
        val requestA = request(0)
        val requestB = ThumbRequest("book-b", 0, ThumbRequest.BUCKET_SMALL)

        val pending = async(Dispatchers.IO) {
            val outcomeA = runCatching { thumbs.load(cancelledSource, requestA) }
            // If cancel(requestA) had cancelled this caller's own job (the pre-fix bug), this second
            // load in the same coroutine would fail too, even though it names a different request.
            val bitmapB = thumbs.load(siblingSource, requestB)
            outcomeA to bitmapB
        }
        // Rendezvous, not a timeout: request A parks in openPage before the cancel can land.
        withContext(Dispatchers.IO) { entered.await() }
        thumbs.cancel(requestA)
        gate.countDown()

        val (outcomeA, bitmapB) = pending.await()
        assertTrue(outcomeA.exceptionOrNull() is CancellationException)
        assertEquals(ThumbRequest.BUCKET_SMALL, bitmapB.width)
        assertEquals(1, siblingSource.reads.get())
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
