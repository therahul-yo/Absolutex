package com.absolutex.remote.core

import com.absolutex.model.BookIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Opening and paging a remote book over a counting transport: every byte of traffic is
 * asserted, so an index walk that pulls the whole file (or a bomb that OOMs) fails here.
 */
class CoreComicSourceTest {

    private fun archive(pages: Int): ByteArray {
        val entries = (1..pages).map { "page%03d.jpg".format(it) to ZipBytes.pageBytes(it) }
        return ZipBytes.cbz(*entries.toTypedArray())
    }

    // Entry bytes plus at most three 64 KiB block-cache runs (header, entry, alignment):
    // the structural ceiling for one page, whatever the payload compresses to.
    private companion object {
        const val MAX_PAGE_TRAFFIC_BYTES = 3L * 64 * 1024
    }

    private fun cdOffsetOf(bytes: ByteArray): Int =
        ZipBytes.u32(bytes, ZipBytes.eocdStart(bytes) + 16).toInt()

    private fun opened(bytes: ByteArray): Pair<CoreComicSource, FakeRangeTransport> {
        val transport = FakeRangeTransport(bytes)
        val result = CoreComicSource.open(transport, "book.cbz")
        assertTrue(result is RemoteOpenResult.Ready)
        return (result as RemoteOpenResult.Ready).source as CoreComicSource to transport
    }

    @Test fun `open indexes without per-entry reads or amplification`() {
        val bytes = archive(400)
        val transport = FakeRangeTransport(bytes)
        val result = CoreComicSource.open(transport, "book.cbz")
        assertTrue(result is RemoteOpenResult.Ready)
        assertEquals(400, (result as RemoteOpenResult.Ready).source.pages.size)
        // No read per entry: the magic sniff plus block-quantised tail/directory spans.
        // Block alignment may widen the tail span to a block edge, but every byte is
        // fetched at most once — traffic never exceeds the file.
        assertTrue("open took ${transport.ranges.size} round trips", transport.ranges.size <= 3)
        assertTrue("indexed ${transport.bytesServed} of ${bytes.size}", transport.bytesServed <= bytes.size + 4)
    }

    @Test fun `page fetch costs the entry plus bounded block overhead`() {
        // Big archive: block-quantised traffic (~64 KiB runs) must still sit far below the
        // file, whatever the payload compresses to — the bound is structural, not a ratio.
        val bytes = archive(400)
        val (source, transport) = opened(bytes)
        source.use {
            val before = transport.bytesServed
            val data = it.openPage(7).use { stream -> stream.readBytes() }
            assertTrue(data.contentEquals(ZipBytes.pageBytes(8)))
            val traffic = transport.bytesServed - before
            assertTrue("page traffic $traffic", traffic < MAX_PAGE_TRAFFIC_BYTES)
        }
    }

    @Test fun `paging back to a read page costs zero transport calls`() {
        val bytes = archive(8)
        val (source, transport) = opened(bytes)
        source.use {
            val third = it.openPage(3).use { stream -> stream.readBytes() }
            it.openPage(5).use { stream -> stream.readBytes() }
            val calls = transport.ranges.size
            val again = it.openPage(3).use { stream -> stream.readBytes() }
            assertTrue(again.contentEquals(third))
            assertEquals(calls, transport.ranges.size)
        }
    }

    @Test fun `identity matches a local copy of the same file`() {
        val bytes = archive(4)
        val transport = FakeRangeTransport(bytes)
        val result = CoreComicSource.open(transport, "book.cbz")
        assertTrue(result is RemoteOpenResult.Ready)
        val ready = result as RemoteOpenResult.Ready
        assertEquals(BookIdentity.of("book.cbz", bytes.size.toLong()), ready.identity)
        assertEquals("book.cbz", ready.displayName)
        assertEquals(bytes.size.toLong(), ready.sizeBytes)
    }

    @Test fun `non-zip magic downloads instead of parsing`() {
        val bytes = ByteArray(4096) { 3 }
        val result = CoreComicSource.open(FakeRangeTransport(bytes), "book.cbr")
        assertTrue(result is RemoteOpenResult.DownloadRequired)
        val reason = (result as RemoteOpenResult.DownloadRequired).reason
        assertTrue(reason.contains("download", ignoreCase = true))
    }

    @Test fun `tiny file downloads instead of parsing`() {
        val result = CoreComicSource.open(FakeRangeTransport(ByteArray(2)), "book.cbz")
        assertTrue(result is RemoteOpenResult.DownloadRequired)
    }

    @Test fun `declared output past the hard cap fails before inflation`() {
        val bytes = ZipBytes.cbz("page01.jpg" to ByteArray(1024 * 1024))
        val patched = bytes.copyOf()
        ZipBytes.le32(patched, cdOffsetOf(patched) + 24, 0x50000000L)
        val (source, _) = opened(patched)
        source.use {
            try {
                it.openPage(0).use { stream -> stream.readBytes() }
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("inflated") == true)
            }
        }
    }

    @Test fun `lying small declared size aborts mid-stream`() {
        val bytes = ZipBytes.cbz("page01.jpg" to ByteArray(1024 * 1024))
        val patched = bytes.copyOf()
        ZipBytes.le32(patched, cdOffsetOf(patched) + 24, 100L)
        val (source, _) = opened(patched)
        source.use {
            try {
                it.openPage(0).use { stream -> stream.readBytes() }
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("declared size") == true)
            }
        }
    }

    @Test fun `empty archive cover fails with a message`() {
        val bytes = ZipBytes.cbz("Thumbs.db" to ByteArray(8))
        val (source, _) = opened(bytes)
        source.use {
            assertTrue(it.pages.isEmpty())
            try {
                it.openCover()
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("no pages") == true)
            }
        }
    }

    @Test fun `close cancels future reads and freezes traffic`() {
        val bytes = archive(4)
        val (source, transport) = opened(bytes)
        source.openPage(0).use { it.readBytes() }
        val calls = transport.ranges.size
        source.close()
        try {
            source.openPage(1)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("closed") == true)
        }
        assertEquals(calls, transport.ranges.size)
    }

    @Test fun `close during a stalled read discards the bytes`() {
        // Layout straddles the 64 KiB block cache: page 2's header sits past the magic
        // block and before the tail window, so its first read must hit the transport.
        // STORED keeps sizes exact (no compression guesswork).
        val bytes = ZipBytes.cbz(
            "page001.jpg" to ByteArray(100_000) { it.toByte() },
            "page002.jpg" to ZipBytes.pageBytes(2),
            "page003.jpg" to ByteArray(100_000) { it.toByte() },
            stored = true,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val baseline = java.util.concurrent.atomic.AtomicInteger(-1)
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val stalled = object : RangeTransport {
            val delegate = FakeRangeTransport(bytes)
            override fun sizeBytes(): Long = delegate.sizeBytes()
            override fun readAt(offset: Long, length: Int): ByteArray {
                // Layout-independent: open takes whatever it takes; the first read after
                // the recorded baseline parks, wherever headers and entries happen to lie.
                if (baseline.get() >= 0 && reads.incrementAndGet() > baseline.get()) {
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                }
                return delegate.readAt(offset, length)
            }
        }
        val result = CoreComicSource.open(stalled, "book.cbz")
        assertTrue(result is RemoteOpenResult.Ready)
        val source = (result as RemoteOpenResult.Ready).source
        baseline.set(reads.get())
        var outcome: Result<ByteArray>? = null
        val worker = thread { outcome = runCatching { source.openPage(1).use { it.readBytes() } } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        source.close()
        release.countDown()
        worker.join(10_000)
        // The bytes arrived after the close: discarded as IOException, never delivered.
        assertTrue(outcome?.exceptionOrNull() is IOException)
    }

    @Test fun `closed stream serves the reopen from cache with zero calls`() {
        val bytes = archive(4)
        val (source, transport) = opened(bytes)
        source.use {
            val first = it.openPage(0).use { stream -> stream.readBytes() }
            val calls = transport.ranges.size
            val second = it.openPage(0).use { stream -> stream.readBytes() }
            assertTrue(second.contentEquals(first))
            assertEquals(calls, transport.ranges.size)
        }
    }
}
