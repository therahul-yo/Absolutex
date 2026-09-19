package com.absolutex.remote.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Cover fetching through the backend seam: the grid passes (serverId, path) and never touches
 * a Uri. Transports are in-memory fakes here; the archive parsing, hostile-entry refusal and
 * session ownership are all real.
 */
class RemoteCoversTest {

    private fun archive(): ByteArray =
        ZipBytes.cbz(
            "cover.jpg" to ZipBytes.pageBytes(3, 2048),
            "page02.jpg" to ZipBytes.pageBytes(4, 4096),
        )

    @Test fun `cover resolves through the seam and closes the session`() = runTest {
        val bytes = archive()
        val transport = FakeRangeTransport(bytes)
        var seenServer = ""
        var seenPath = ""
        val fetcher = TransportCoverFetcher { serverId, path ->
            seenServer = serverId
            seenPath = path
            transport
        }
        val cover = fetcher.coverBytes("nas", "/comics/book.cbz")
        assertEquals("/comics/book.cbz", seenPath)
        assertEquals("nas", seenServer)
        assertTrue(cover.contentEquals(ZipBytes.pageBytes(3, 2048)))
        // Whoever opens the book owns the transport: the grid must not leak a session per cell.
        assertEquals(1, transport.closes)
    }

    @Test fun `non-zip degrades to a download verdict, never bytes`() = runTest {
        val bytes = ByteArray(4096) { 0x41.toByte() }
        val transport = FakeRangeTransport(bytes)
        val fetcher = TransportCoverFetcher { _, _ -> transport }
        try {
            fetcher.coverBytes("nas", "/comics/book.cbr")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("download") == true)
        }
        assertEquals(1, transport.closes)
    }

    @Test fun `transport failure propagates as IOException`() = runTest {
        val fetcher = TransportCoverFetcher { _, _ -> throw IOException("server down") }
        try {
            fetcher.coverBytes("nas", "/comics/book.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("server down") == true)
        }
    }

    @Test fun `empty archive fails with a message, not IndexOutOfBounds`() = runTest {
        // A valid ZIP whose only entry is junk: indexing succeeds with zero pages, so the
        // cover must fail like an unreadable book, not with IndexOutOfBounds.
        val transport = FakeRangeTransport(ZipBytes.cbz("Thumbs.db" to ByteArray(8)))
        val fetcher = TransportCoverFetcher { _, _ -> transport }
        try {
            fetcher.coverBytes("nas", "/comics/empty.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("no pages") == true)
        }
        assertEquals(1, transport.closes)
    }

    @Test fun `giant first entry is refused without inflating it`() = runTest {
        // The directory declares a 64 MiB compressed first entry: the fetch must fail on the
        // numbers before its bytes can grow anywhere near that.
        val bytes = archive()
        val central = ZipBytes.u32(bytes, ZipBytes.eocdStart(bytes) + EOCD_CD_OFFSET).toInt()
        ZipBytes.le32(bytes, central + CENTRAL_COMP_SIZE, GIANT_ENTRY_BYTES)
        val transport = FakeRangeTransport(bytes)
        val fetcher = TransportCoverFetcher { _, _ -> transport }
        try {
            fetcher.coverBytes("nas", "/comics/bomb.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("too large") == true)
        }
        assertEquals(1, transport.closes)
    }

    @Test fun `caller cap below the cover size refuses the transfer`() = runTest {
        val transport = FakeRangeTransport(archive())
        val fetcher = TransportCoverFetcher { _, _ -> transport }
        try {
            fetcher.coverBytes("nas", "/comics/book.cbz", maxBytes = 16)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("too large") == true)
        }
        assertEquals(1, transport.closes)
    }

    private companion object {
        const val EOCD_CD_OFFSET = 16
        const val CENTRAL_COMP_SIZE = 20
        const val GIANT_ENTRY_BYTES = 64L * 1024 * 1024
    }
}
