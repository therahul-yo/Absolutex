package com.absolutex.source.epub

import com.absolutex.model.ReadingFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** §2/§6: an EPUB opens as a ComicSource, or says clearly why it cannot. */
class EpubComicSourceTest {

    @Test fun `a fixed-layout epub opens as a comic in spine order`() {
        val source = comic(epub())
        assertEquals(
            listOf("OEBPS/img/p1.jpg", "OEBPS/img/p2.jpg", "OEBPS/img/p3.jpg"),
            source.pages.map { it.entryName },
        )
        assertEquals(listOf(0, 1, 2), source.pages.map { it.index })
    }

    @Test fun `pages read their own bytes`() {
        val source = comic(epub())
        assertEquals("image-p2", String(source.openPage(1).use { it.readBytes() }))
    }

    @Test fun `an rtl package reports RTL through comicInfo`() {
        // Not a new ComicSource member: this is the channel a manga CBZ's <Manga> tag uses, so
        // one consumer serves both.
        assertEquals(ReadingFlow.RTL, comic(epub(rtl = true)).comicInfo?.readingFlow)
    }

    @Test fun `a package saying nothing about direction reports no comicInfo at all`() {
        // Null, not ComicInfo(readingFlow = null): "the file did not say" must stay
        // distinguishable from "the file said left-to-right".
        assertNull(comic(epub()).comicInfo)
    }

    @Test fun `a declared cover that is not page one is the cover`() {
        val source = comic(epub(cover = true))
        assertEquals("cover-art", String(source.openCover().use { it.readBytes() }))
    }

    @Test fun `with no declared cover the cover is page one`() {
        assertEquals("image-p1", String(comic(epub()).openCover().use { it.readBytes() }))
    }

    @Test fun `a cover entry that cannot be read falls back to page one`() {
        // The cover must stay IN the entry list and fail only when read. Deleting it instead
        // makes the manifest drop the item, so coverEntryName is null and the fallback under
        // test is never reached — the first version of this test passed for that reason.
        val files = epub(cover = true)
        val source = (
            EpubComicSource.open(files.keys.toList()) { name ->
                if (name == "OEBPS/img/cover.jpg") null else files[name]?.toByteArray()
            } as EpubComicSource.Result.Comic
            ).source
        assertEquals("image-p1", String(source.openCover().use { it.readBytes() }))
    }

    @Test fun `a text epub is its own verdict, not an empty comic`() {
        val files = epub(pageDoc = { "<html><body><p>Call me Ishmael.</p></body></html>" })
        assertSame(EpubComicSource.Result.TextEpub, EpubComicSource.open(files.keys.toList(), reader(files)))
    }

    @Test fun `something that is not an epub says so`() {
        val files = mapOf("random.txt" to "not an epub")
        assertSame(EpubComicSource.Result.NotAnEpub, EpubComicSource.open(files.keys.toList(), reader(files)))
    }

    @Test fun `a page that cannot be read surfaces as IOException`() {
        val files = epub().toMutableMap()
        val source = comic(files)
        files.remove("OEBPS/img/p2.jpg")
        assertThrows(IOException::class.java) { source.openPage(1) }
    }

    @Test fun `an out of range page is a bounds error naming the book's size`() {
        val source = comic(epub())
        val e = assertThrows(IndexOutOfBoundsException::class.java) { source.openPage(3) }
        assertEquals("page 3 of 3", e.message)
    }

    // ---- the one-pass cache ---------------------------------------------------------------

    @Test fun `the cache skips page images and keeps the package documents`() {
        // The measured reason this exists: images are the bulk and are never parsed, only named.
        val bytes = realZip(epub() + mapOf("OEBPS/img/huge.jpg" to "x".repeat(4096)))
        val cache = EpubComicSource.packageEntryCache { ByteArrayInputStream(bytes) }
        assertTrue("mimetype" in cache)
        assertTrue("META-INF/container.xml" in cache)
        assertTrue("OEBPS/content.opf" in cache)
        assertTrue("OEBPS/text/p1.xhtml" in cache)
        assertTrue("no image should be cached", cache.keys.none { it.endsWith(".jpg") })
    }

    @Test fun `a book opened through the cache resolves the same pages`() {
        val files = epub()
        val bytes = realZip(files)
        val cache = EpubComicSource.packageEntryCache { ByteArrayInputStream(bytes) }
        // The parse reads only from the cache; page reads still go to the archive.
        val result = EpubComicSource.open(files.keys.toList()) { name ->
            cache[name] ?: files[name]?.toByteArray()
        }
        assertEquals(
            comic(files).pages.map { it.entryName },
            (result as EpubComicSource.Result.Comic).source.pages.map { it.entryName },
        )
    }

    @Test fun `an implausibly large package document is not cached whole`() {
        // A hostile EPUB naming a huge blob "content.opf" must not be read into memory.
        val big = "y".repeat(2 * 1024 * 1024)
        val bytes = realZip(epub() + mapOf("OEBPS/big.opf" to big))
        val cache = EpubComicSource.packageEntryCache { ByteArrayInputStream(bytes) }
        assertTrue("OEBPS/big.opf" !in cache)
        assertTrue("the small ones still cache", "OEBPS/content.opf" in cache)
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun comic(files: Map<String, String>): EpubComicSource =
        (EpubComicSource.open(files.keys.toList(), reader(files)) as EpubComicSource.Result.Comic).source

    private fun reader(files: Map<String, String>): (String) -> ByteArray? =
        { name -> files[name]?.toByteArray(Charsets.UTF_8) }

    private fun realZip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, body) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun epub(
        rtl: Boolean = false,
        cover: Boolean = false,
        pageDoc: (String) -> String = { img -> svg(img) },
    ): Map<String, String> {
        val ids = listOf("p1", "p2", "p3")
        val files = LinkedHashMap<String, String>()
        files["mimetype"] = "application/epub+zip"
        files["META-INF/container.xml"] = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf"
                media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val items = ids.joinToString("") {
            """<item id="$it" href="text/$it.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="${it}i" href="img/$it.jpg" media-type="image/jpeg"/>"""
        }
        val coverItem = if (cover) {
            """<item id="cov" href="img/cover.jpg" media-type="image/jpeg" properties="cover-image"/>"""
        } else {
            ""
        }
        val dir = if (rtl) """ page-progression-direction="rtl"""" else ""
        files["OEBPS/content.opf"] = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata/>
              <manifest>$items$coverItem</manifest>
              <spine$dir>${ids.joinToString("") { """<itemref idref="$it"/>""" }}</spine>
            </package>"""
        for (id in ids) {
            files["OEBPS/text/$id.xhtml"] = pageDoc("../img/$id.jpg")
            files["OEBPS/img/$id.jpg"] = "image-$id"
        }
        if (cover) files["OEBPS/img/cover.jpg"] = "cover-art"
        return files
    }

    private fun svg(img: String): String =
        """<html xmlns:xlink="http://www.w3.org/1999/xlink"><body><svg viewBox="0 0 1 1">""" +
            """<image xlink:href="$img"/></svg></body></html>"""
}
