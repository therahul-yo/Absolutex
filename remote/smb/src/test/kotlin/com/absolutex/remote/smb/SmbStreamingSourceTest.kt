package com.absolutex.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SmbStreamingSourceTest {

    private fun sourceOf(
        transport: FakeSmbTransport,
        archive: ByteArray,
    ): SmbStreamingSource = SmbStreamingSource.open(transport, "book.cbz").also {
        // Keep the fixture reachable for assertions without reopening.
        check(archive.isNotEmpty())
    }

    @Test fun `pages natural-sort and filter junk`() {
        val archive = ZipFixtures.cbz(
            "page10.jpg" to ZipFixtures.pageBytes(10),
            "Thumbs.db" to ByteArray(8),
            "__MACOSX/._page02.jpg" to ByteArray(8),
            "page02.jpg" to ZipFixtures.pageBytes(2),
            "ComicInfo.xml" to ByteArray(8),
        )
        val transport = FakeSmbTransport(mapOf("book.cbz" to archive))
        sourceOf(transport, archive).use { source ->
            assertEquals(listOf("page02.jpg", "page10.jpg"), source.pages.map { it.entryName })
        }
    }

    @Test fun `page bytes round-trip without full download`() {
        val first = ZipFixtures.pageBytes(1, 2048)
        val archive = ZipFixtures.cbz(
            "page01.jpg" to first,
            "page02.jpg" to ZipFixtures.pageBytes(2, 4096),
            ZipFixtures.paddingEntry(),
            storedNames = setOf("preview.dat"),
        )
        val transport = FakeSmbTransport(mapOf("book.cbz" to archive))
        sourceOf(transport, archive).use { source ->
            // Proportions, not milliseconds: traffic must scale with the page, not the file.
            val before = transport.bytesServed
            val bytes = source.openPage(0).use { it.readBytes() }
            assertTrue(bytes.contentEquals(first))
            val pageTraffic = transport.bytesServed - before
            assertTrue("page traffic $pageTraffic of ${archive.size}", pageTraffic < archive.size / 4)
        }
    }

    @Test fun `cover costs the first entry only`() {
        val archive = ZipFixtures.cbz(
            "cover.jpg" to ZipFixtures.pageBytes(9, 2048),
            "page02.jpg" to ZipFixtures.pageBytes(2, 8192),
            "page03.jpg" to ZipFixtures.pageBytes(3, 8192),
            ZipFixtures.paddingEntry(padBytes = 2 * 1024 * 1024),
            storedNames = setOf("preview.dat"),
        )
        val transport = FakeSmbTransport(mapOf("book.cbz" to archive))
        sourceOf(transport, archive).use { source ->
            val cover = RemoteThumbnails.coverBytes(source)
            assertTrue(cover.contentEquals(ZipFixtures.pageBytes(9, 2048)))
            val traffic = transport.bytesServed
            assertTrue("cover traffic $traffic of ${archive.size}", traffic < archive.size / 4)
        }
    }

    @Test fun `non-zip remote fails fast with plain message`() {
        val bytes = ByteArray(4096) { 3 }
        val transport = FakeSmbTransport(mapOf("book.cbz" to bytes))
        try {
            SmbStreamingSource.open(transport, "book.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("zip/cbz") == true)
        }
    }

    @Test fun `stored entries stream through the cache`() {
        val first = ZipFixtures.pageBytes(5, 3000)
        val archive = ZipFixtures.cbz("page01.png" to first, stored = true)
        val transport = FakeSmbTransport(mapOf("book.cbz" to archive))
        sourceOf(transport, archive).use { source ->
            assertTrue(source.openPage(0).use { it.readBytes() }.contentEquals(first))
        }
    }

    @Test fun `cover on an empty archive fails with a message, not IndexOutOfBounds`() {
        val archive = ZipFixtures.cbz("Thumbs.db" to ByteArray(8))
        val transport = FakeSmbTransport(mapOf("book.cbz" to archive))
        sourceOf(transport, archive).use { source ->
            assertTrue(source.pages.isEmpty())
            try {
                RemoteThumbnails.coverBytes(source)
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("no pages") == true)
            }
        }
    }

    @Test fun `closing a deflated page ends its inflater`() {
        val raw = ZipFixtures.pageBytes(7, 2048)
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArray(4096)
        val compressedLen = deflater.deflate(compressed)
        deflater.end()
        val spy = SpyInflater()
        DeflateStream(compressed.copyOf(compressedLen), spy).use { stream ->
            assertTrue(stream.readBytes().contentEquals(raw))
        }
        assertTrue("inflater was not ended on close", spy.ended)
    }

    private class SpyInflater : java.util.zip.Inflater(true) {
        var ended = false

        override fun end() {
            ended = true
            super.end()
        }
    }
}
