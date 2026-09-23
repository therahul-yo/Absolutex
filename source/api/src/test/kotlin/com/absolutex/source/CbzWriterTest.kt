package com.absolutex.source

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Test

/** 2020-01-01T00:00:00Z — the instant CbzWriter stamps every entry with. */
private const val FIXED_MILLIS = 1_577_836_800_000L

private fun page(name: String, body: ByteArray) = CbzPage(name) { ByteArrayInputStream(body) }

private fun pagesOf(count: Int, extension: String = "jpg") = (0 until count).map {
    page("scan_$it.$extension", byteArrayOf(it.toByte()))
}

private fun writeToBytes(pages: List<CbzPage>): ByteArray =
    ByteArrayOutputStream().also { writeCbz(pages, it) }.toByteArray()

private class ReadEntry(
    val name: String,
    val method: Int,
    val size: Long,
    val compressedSize: Long,
    val time: Long,
    val body: ByteArray,
)

private fun readBack(archive: ByteArray): List<ReadEntry> {
    val out = mutableListOf<ReadEntry>()
    ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val body = zip.readBytes()
            out += ReadEntry(entry.name, entry.method, entry.size, entry.compressedSize, entry.time, body)
        }
    }
    return out
}

class CbzWriterTest {

    @Test
    fun `pages are numbered from one and padded to three digits`() {
        val names = readBack(writeToBytes(pagesOf(3))).map { it.name }
        assertEquals(listOf("001.jpg", "002.jpg", "003.jpg"), names)
    }

    /**
     * The padding width must follow the book, not a constant: a fixed three digits would write
     * "1000.jpg" after "999.jpg", which only orders correctly by luck of equal length, and would
     * genuinely misorder a wider book. Asserting on a 1,000-page export is what makes a
     * hard-coded width fail.
     */
    @Test
    fun `padding widens for a book that needs a fourth digit`() {
        val names = readBack(writeToBytes(pagesOf(1000))).map { it.name }
        assertEquals("0001.jpg", names.first())
        assertEquals("1000.jpg", names.last())
        assertTrue("padded names must sort as they read", names == names.sorted())
    }

    /** Guards the same property the padding exists for, without the 1,000-entry archive. */
    @Test
    fun `entry name keeps the source extension and drops the source name`() {
        assertEquals("001.png", cbzEntryName(index = 0, pageCount = 9, originalName = "cover.png"))
        assertEquals("012.webp", cbzEntryName(index = 11, pageCount = 99, originalName = "a/b/p12.WEBP"))
        assertEquals("0100.jpg", cbzEntryName(index = 99, pageCount = 1000, originalName = "x.jpg"))
    }

    @Test
    fun `a page with no extension is written as a bare ordinal`() {
        assertEquals("001", cbzEntryName(index = 0, pageCount = 1, originalName = "no_extension"))
    }

    /**
     * Pages are already-compressed images, so entries are STORED. Asserting the method alone is
     * not enough — a deflate that happened to shrink nothing would still report its own method —
     * so this also pins compressedSize to size, which only holds when the bytes went in raw.
     */
    @Test
    fun `entries are stored rather than deflated`() {
        // Highly compressible on purpose: were this deflated, compressedSize would fall far below size.
        val body = ByteArray(4096) { 'A'.code.toByte() }
        val entries = readBack(writeToBytes(listOf(page("p.jpg", body))))
        assertEquals(1, entries.size)
        assertEquals(ZipEntry.STORED.toLong(), entries[0].method.toLong())
        assertEquals(body.size.toLong(), entries[0].size)
        assertEquals(body.size.toLong(), entries[0].compressedSize)
    }

    @Test
    fun `page bytes survive the round trip in reading order`() {
        val bodies = listOf("first".toByteArray(), "second".toByteArray(), "third".toByteArray())
        val entries = readBack(writeToBytes(bodies.mapIndexed { i, b -> page("p$i.jpg", b) }))
        assertEquals(bodies.size, entries.size)
        bodies.forEachIndexed { i, expected -> assertArrayEquals(expected, entries[i].body) }
    }

    /**
     * Two exports of one book must be byte-identical. A writer that let entries take the current
     * time would pass a naive equality check whenever both writes landed inside the same two-second
     * DOS tick, so the stamped instant is pinned directly as well.
     */
    @Test
    fun `the same book exports to the same bytes, and the stamp is the fixed instant`() {
        val pages = pagesOf(3)
        assertArrayEquals(writeToBytes(pages), writeToBytes(pages))
        readBack(writeToBytes(pages)).forEach { assertEquals(FIXED_MILLIS, it.time) }
    }

    /**
     * §2 says degrade rather than crash when *reading* a damaged container, but an export that
     * dropped the pages it could not read would hand back a quietly wrong book. Failing loudly is
     * the behaviour, and the caller discards the partial file.
     */
    @Test
    fun `a page that cannot be read fails the export instead of being skipped`() {
        val pages = listOf(
            page("ok.jpg", "ok".toByteArray()),
            CbzPage("broken.jpg") { object : InputStream() {
                override fun read(): Int = throw IOException("page is unreadable")
            } },
            page("also_ok.jpg", "fine".toByteArray()),
        )
        try {
            writeToBytes(pages)
            fail("expected the unreadable page to fail the export")
        } catch (expected: IOException) {
            assertEquals("page is unreadable", expected.message)
        }
    }

    /** The writer finishes the archive but does not close a stream it does not own. */
    @Test
    fun `the caller's stream is left open`() {
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        writeCbz(pagesOf(2), out)
        assertTrue("archive must be complete", readBack(out.toByteArray()).size == 2)
        assertTrue("writeCbz must not close the caller's stream", !closed)
    }

    @Test
    fun `an empty selection writes a valid empty archive`() {
        assertEquals(emptyList<ReadEntry>(), readBack(writeToBytes(emptyList())))
    }
}

/** The ComicInfo.xml an export carries, and how it differs from the pages around it. */
class CbzWriterComicInfoTest {

    private val info = com.absolutex.model.ComicInfo(
        series = "Absolute Batman",
        number = com.absolutex.model.IssueNumber.parse("1"),
        pageCount = 2,
    )

    @Test
    fun `no metadata is written when none is given`() {
        val names = readBack(writeToBytes(pagesOf(2))).map { it.name }
        assertEquals(listOf("001.jpg", "002.jpg"), names)
    }

    /** First, so a reader streaming the archive meets the metadata before the pages. */
    @Test
    fun `ComicInfo is written first, ahead of the pages`() {
        val names = readBack(cbzWith(info)).map { it.name }
        assertEquals(listOf("ComicInfo.xml", "001.jpg", "002.jpg"), names)
    }

    /**
     * Markup compresses and images do not, which is the whole reason the pages are STORED. Pinning
     * the method keeps a later "make everything consistent" tidy-up from silently storing the XML
     * — or, worse, deflating the images.
     */
    @Test
    fun `ComicInfo is deflated while the pages stay stored`() {
        val entries = readBack(cbzWith(info)).associateBy { it.name }
        assertEquals(ZipEntry.DEFLATED.toLong(), entries.getValue("ComicInfo.xml").method.toLong())
        assertEquals(ZipEntry.STORED.toLong(), entries.getValue("001.jpg").method.toLong())
    }

    @Test
    fun `the metadata in the archive parses back to what went in`() {
        val xml = readBack(cbzWith(info)).first { it.name == "ComicInfo.xml" }.body.toString(Charsets.UTF_8)
        assertEquals(info, ComicInfoParser.parse(xml))
    }

    /** An archive carrying metadata must still export identically twice on one device. */
    @Test
    fun `an archive with metadata is still reproducible`() {
        assertArrayEquals(cbzWith(info), cbzWith(info))
    }

    private fun cbzWith(info: com.absolutex.model.ComicInfo): ByteArray =
        ByteArrayOutputStream().also { writeCbz(pagesOf(2), it, info) }.toByteArray()
}
