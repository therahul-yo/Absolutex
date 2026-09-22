package com.absolutex.source.epub

import com.absolutex.model.ReadingFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2/§6: a fixed-layout comic EPUB opens in spine order; a text EPUB is detected and refused
 * clearly rather than opened as a book with no pages.
 *
 * The parser is handed entry names and a reader, so every case here is a map — which is the
 * point of that seam: none of this needs a ZIP, a device, or a real book.
 */
class EpubPackageTest {

    @Test fun `a fixed-layout epub resolves its pages in spine order`() {
        val book = parse(epub(spine = listOf("p3", "p1", "p2")))
        assertEquals(
            listOf("OEBPS/img/p3.jpg", "OEBPS/img/p1.jpg", "OEBPS/img/p2.jpg"),
            (book as EpubBook.Pages).entryNames,
        )
    }

    @Test fun `spine order wins over manifest order`() {
        // The manifest is a bag of files; only the spine is reading order.
        val book = parse(epub(spine = listOf("p2", "p3", "p1")))
        assertEquals(listOf("p2", "p3", "p1").map { "OEBPS/img/$it.jpg" }, pagesOf(book))
    }

    @Test fun `rtl page progression is carried through`() {
        val book = parse(epub(direction = "rtl"))
        assertEquals(ReadingFlow.RTL, (book as EpubBook.Pages).readingFlow)
    }

    @Test fun `rtl is matched case-insensitively`() {
        assertEquals(ReadingFlow.RTL, (parse(epub(direction = "RTL")) as EpubBook.Pages).readingFlow)
    }

    @Test fun `no declared direction stays null rather than guessing left-to-right`() {
        // Null means "the book said nothing", so the reader's own default applies. Answering LTR
        // here would silently overrule a user's manga default on every western-authored file.
        assertNull((parse(epub(direction = null)) as EpubBook.Pages).readingFlow)
        assertNull((parse(epub(direction = "ltr")) as EpubBook.Pages).readingFlow)
    }

    // ---- how a page document points at its image ----------------------------------------

    @Test fun `an svg image href resolves`() {
        // Kindle Comic Creator's shape: the page wrapped in <svg> so it scales to the viewport.
        val files = epub(spine = listOf("p1"), pageDoc = { img -> svgDoc(img) })
        assertEquals(listOf("OEBPS/img/p1.jpg"), pagesOf(parse(files)))
    }

    @Test fun `a plain img src resolves`() {
        val files = epub(spine = listOf("p1"), pageDoc = { img -> "<html><body><img src=\"$img\"/></body></html>" })
        assertEquals(listOf("OEBPS/img/p1.jpg"), pagesOf(parse(files)))
    }

    @Test fun `a page image is found however deeply it is nested`() {
        val files = epub(
            spine = listOf("p1"),
            pageDoc = { img -> "<html><body><div><section><p><img src=\"$img\"/></p></section></div></body></html>" },
        )
        assertEquals(listOf("OEBPS/img/p1.jpg"), pagesOf(parse(files)))
    }

    @Test fun `the first image in document order wins`() {
        val files = epub(
            spine = listOf("p1"),
            pageDoc = { img -> "<html><body><img src=\"$img\"/><img src=\"../img/decoration.jpg\"/></body></html>" },
            extra = mapOf("OEBPS/img/decoration.jpg" to "x"),
        )
        assertEquals(listOf("OEBPS/img/p1.jpg"), pagesOf(parse(files)))
    }

    @Test fun `an image reference that names nothing in the container is not a page`() {
        val files = epub(
            spine = listOf("p1"),
            pageDoc = { "<html><body><img src=\"../img/missing.jpg\"/></body></html>" },
        )
        assertSame(EpubBook.Reflowable, parse(files))
    }

    @Test fun `a reference that is not an image is not a page`() {
        val files = epub(
            spine = listOf("p1"),
            pageDoc = { "<html><body><img src=\"../notes.txt\"/></body></html>" },
            extra = mapOf("OEBPS/notes.txt" to "not an image"),
        )
        assertSame(EpubBook.Reflowable, parse(files))
    }

    // ---- the text-EPUB verdict -----------------------------------------------------------

    @Test fun `a reflowable text epub is detected, not opened as an empty book`() {
        val files = epub(spine = listOf("p1", "p2"), pageDoc = { "<html><body><p>Call me Ishmael.</p></body></html>" })
        assertSame(EpubBook.Reflowable, parse(files))
    }

    @Test fun `a book where only some documents carry an image is reflowable`() {
        // A novel with one illustration is not a comic missing its pages. Opening it as one
        // would silently drop its prose.
        val files = buildEpub(spine = listOf("p1", "p2")).toMutableMap()
        files["OEBPS/text/p2.xhtml"] = "<html><body><p>prose, no picture</p></body></html>"
        assertSame(EpubBook.Reflowable, EpubPackage.parse(files.keys.toList(), reader(files)))
    }

    // ---- malformed --------------------------------------------------------------------

    @Test fun `no container xml is malformed`() {
        val files = buildEpub().filterKeys { it != "META-INF/container.xml" }
        assertSame(EpubBook.Malformed, EpubPackage.parse(files.keys.toList(), reader(files)))
    }

    @Test fun `a container pointing at a missing package document is malformed`() {
        val files = buildEpub().toMutableMap()
        files["META-INF/container.xml"] = container("OEBPS/nowhere.opf")
        assertSame(EpubBook.Malformed, EpubPackage.parse(files.keys.toList(), reader(files)))
    }

    @Test fun `unparseable xml is a verdict, not an exception`() {
        val files = buildEpub().toMutableMap()
        files["OEBPS/content.opf"] = "<package><manifest>truncated..."
        assertSame(EpubBook.Malformed, EpubPackage.parse(files.keys.toList(), reader(files)))
    }

    @Test fun `an empty spine is malformed`() {
        assertSame(EpubBook.Malformed, parse(epub(spine = emptyList())))
    }

    @Test fun `an entry that cannot be read is a verdict, not an exception`() {
        val files = buildEpub()
        val book = EpubPackage.parse(files.keys.toList()) { name ->
            if (name == "OEBPS/content.opf") null else files[name]?.toByteArray()
        }
        assertSame(EpubBook.Malformed, book)
    }

    @Test fun `an xml document past the size cap is refused rather than parsed`() {
        // These arrive compressed from an untrusted container: a small entry can inflate into a
        // DOM that exhausts memory, and OutOfMemoryError is not an Exception to catch.
        //
        // The oversized document is otherwise PERFECTLY VALID — padded with a comment rather
        // than corrupted — so the cap is the only thing that can refuse it. An invalid giant
        // would be rejected for being invalid, and would pass this test with the cap deleted.
        val files = buildEpub().toMutableMap()
        val padding = "<!--" + "x".repeat(EpubPackage.MAX_XML_BYTES.toInt()) + "-->"
        files["OEBPS/content.opf"] = files.getValue("OEBPS/content.opf")
            .replace("<manifest>", "$padding<manifest>")
        assertTrue(files.getValue("OEBPS/content.opf").length > EpubPackage.MAX_XML_BYTES)
        assertSame(EpubBook.Malformed, EpubPackage.parse(files.keys.toList(), reader(files)))
    }

    @Test fun `the same document under the cap parses, so the cap is what refused it`() {
        // The control for the test above: same shape, padding just under the limit.
        val files = buildEpub().toMutableMap()
        val padding = "<!--" + "x".repeat(1024) + "-->"
        files["OEBPS/content.opf"] = files.getValue("OEBPS/content.opf")
            .replace("<manifest>", "$padding<manifest>")
        assertTrue(EpubPackage.parse(files.keys.toList(), reader(files)) is EpubBook.Pages)
    }

    @Test fun `an external entity is not resolved and does not throw`() {
        // XXE: the package document comes from a file a stranger produced.
        val files = buildEpub().toMutableMap()
        files["OEBPS/content.opf"] = """<?xml version="1.0"?>
            <!DOCTYPE package [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <package><manifest/><spine><itemref idref="&xxe;"/></spine></package>"""
        val book = EpubPackage.parse(files.keys.toList(), reader(files))
        assertTrue("expected a verdict, got $book", book is EpubBook.Malformed || book is EpubBook.Reflowable)
    }

    // ---- covers -------------------------------------------------------------------------

    @Test fun `an epub3 cover-image property is the cover`() {
        val files = buildEpub(
            coverItem = """<item id="cov" href="img/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""",
            extra = mapOf("OEBPS/img/cover.jpg" to "c"),
        )
        val book = EpubPackage.parse(files.keys.toList(), reader(files)) as EpubBook.Pages
        assertEquals("OEBPS/img/cover.jpg", book.coverEntryName)
    }

    @Test fun `an epub2 meta cover is the cover`() {
        val files = buildEpub(
            coverItem = """<item id="cov" href="img/cover.jpg" media-type="image/jpeg"/>""",
            coverMeta = """<meta name="cover" content="cov"/>""",
            extra = mapOf("OEBPS/img/cover.jpg" to "c"),
        )
        val book = EpubPackage.parse(files.keys.toList(), reader(files)) as EpubBook.Pages
        assertEquals("OEBPS/img/cover.jpg", book.coverEntryName)
    }

    @Test fun `a cover that is already the first page is not carried separately`() {
        val files = buildEpub(
            coverItem = """<item id="cov" href="img/p1.jpg" media-type="image/jpeg" properties="cover-image"/>""",
        )
        val book = EpubPackage.parse(files.keys.toList(), reader(files)) as EpubBook.Pages
        assertNull(book.coverEntryName)
    }

    @Test fun `no declared cover is null`() {
        assertNull((parse(epub()) as EpubBook.Pages).coverEntryName)
    }

    // ---- fixtures ------------------------------------------------------------------------

    private fun pagesOf(book: EpubBook): List<String> = (book as EpubBook.Pages).entryNames

    private fun parse(files: Map<String, String>): EpubBook =
        EpubPackage.parse(files.keys.toList(), reader(files))

    private fun reader(files: Map<String, String>): (String) -> ByteArray? =
        { name -> files[name]?.toByteArray(Charsets.UTF_8) }

    private fun epub(
        spine: List<String> = listOf("p1", "p2", "p3"),
        direction: String? = null,
        pageDoc: (String) -> String = { img -> svgDoc(img) },
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildEpub(spine, direction, pageDoc, extra)

    private fun svgDoc(img: String): String =
        """<html xmlns:xlink="http://www.w3.org/1999/xlink"><body><svg viewBox="0 0 1 1">""" +
            """<image xlink:href="$img"/></svg></body></html>"""

    /** A conforming OCF layout: text/ documents pointing up and across into img/. */
    private fun buildEpub(
        spine: List<String> = listOf("p1", "p2", "p3"),
        direction: String? = null,
        pageDoc: (String) -> String = { img -> svgDoc(img) },
        extra: Map<String, String> = emptyMap(),
        coverItem: String = "",
        coverMeta: String = "",
    ): Map<String, String> {
        val ids = listOf("p1", "p2", "p3")
        val files = LinkedHashMap<String, String>()
        files["mimetype"] = "application/epub+zip"
        files["META-INF/container.xml"] = container("OEBPS/content.opf")
        val items = ids.joinToString("\n") { id ->
            """<item id="$id" href="text/$id.xhtml" media-type="application/xhtml+xml"/>""" +
                """<item id="${id}img" href="img/$id.jpg" media-type="image/jpeg"/>"""
        }
        val itemrefs = spine.joinToString("\n") { """<itemref idref="$it"/>""" }
        val dir = direction?.let { """ page-progression-direction="$it"""" } ?: ""
        files["OEBPS/content.opf"] = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata>$coverMeta</metadata>
              <manifest>$items$coverItem</manifest>
              <spine$dir>$itemrefs</spine>
            </package>"""
        for (id in ids) {
            files["OEBPS/text/$id.xhtml"] = pageDoc("../img/$id.jpg")
            files["OEBPS/img/$id.jpg"] = "jpeg-bytes-$id"
        }
        files.putAll(extra)
        return files
    }

    private fun container(opfPath: String): String = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""
}
