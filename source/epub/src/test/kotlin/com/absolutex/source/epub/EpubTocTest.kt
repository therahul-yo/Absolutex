package com.absolutex.source.epub

import com.absolutex.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A text EPUB's contents: EPUB 3 nav first, EPUB 2 NCX as the fallback, never a crash. */
class EpubTocTest {

    private val spine = listOf("OEBPS/text/c1.xhtml", "OEBPS/text/c2.xhtml", "OEBPS/text/c3.xhtml")

    @Test fun `an epub3 nav gives nested entries mapped to spine indices`() {
        val nav = navDoc(
            """
            <li><a href="text/c1.xhtml">Prologue</a></li>
            <li><span>Part One</span>
              <ol>
                <li><a href="text/c2.xhtml#start">Chapter
                    One</a></li>
                <li><a href="text/c3.xhtml">Chapter Two</a></li>
              </ol>
            </li>
            """,
        )
        assertEquals(
            listOf(TocEntry("Prologue", 0, 0), TocEntry("Chapter One", 1, 1), TocEntry("Chapter Two", 2, 1)),
            parse(book(nav = nav)),
        )
    }

    @Test fun `the toc nav wins over a landmarks nav that comes first`() {
        val nav = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="landmarks"><ol><li><a href="text/c3.xhtml">Start</a></li></ol></nav>
            <nav epub:type="toc"><ol><li><a href="text/c1.xhtml">One</a></li></ol></nav>
            </body></html>"""
        assertEquals(listOf(TocEntry("One", 0, 0)), parse(book(nav = nav)))
    }

    @Test fun `an ncx is read when there is no nav document`() {
        val ncx = """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint id="a"><navLabel><text>One</text></navLabel><content src="text/c1.xhtml"/>
              <navPoint id="b"><navLabel><text>One point five</text></navLabel><content src="text/c2.xhtml"/></navPoint>
            </navPoint>
            <navPoint id="c"><navLabel><text>Three</text></navLabel><content src="text/c3.xhtml"/></navPoint>
            </navMap></ncx>"""
        assertEquals(
            listOf(TocEntry("One", 0, 0), TocEntry("One point five", 1, 1), TocEntry("Three", 2, 0)),
            parse(book(ncx = ncx)),
        )
    }

    @Test fun `an empty nav falls back to the ncx`() {
        val ncx = """<ncx><navMap><navPoint><navLabel><text>One</text></navLabel>
            <content src="text/c1.xhtml"/></navPoint></navMap></ncx>"""
        assertEquals(listOf(TocEntry("One", 0, 0)), parse(book(nav = navDoc(""), ncx = ncx)))
    }

    @Test fun `entries outside the spine, remote links and blank titles are dropped`() {
        val nav = navDoc(
            """
            <li><a href="text/missing.xhtml">Gone</a></li>
            <li><a href="https://example.com/c1.xhtml">Remote</a></li>
            <li><a href="text/c2.xhtml">   </a></li>
            <li><a href="text/c3.xhtml">Kept</a></li>
            """,
        )
        assertEquals(listOf(TocEntry("Kept", 2, 0)), parse(book(nav = nav)))
    }

    @Test fun `malformed input yields an empty list, never an exception`() {
        assertEquals(emptyList<TocEntry>(), parse(book(nav = "<html><nav><ol><li>")))
        assertEquals(emptyList<TocEntry>(), parse(emptyMap()))
        assertEquals(emptyList<TocEntry>(), parse(book()))
    }

    @Test fun `nesting past the depth cap is cut off rather than recursed into`() {
        var inner = "<li><a href=\"text/c3.xhtml\">Deep</a></li>"
        repeat(EpubToc.MAX_DEPTH + 3) { inner = "<li><span>x</span><ol>$inner</ol></li>" }
        assertTrue(parse(book(nav = navDoc(inner))).isEmpty())
    }

    @Test fun `a web novel's 2,700 chapter contents parses quickly`() {
        val count = 2_734
        val chapters = (1..count).map { "OEBPS/text/c$it.xhtml" }
        val items = (1..count).joinToString("") { "<li><a href=\"text/c$it.xhtml\">Chapter $it</a></li>" }
        val files = book(nav = navDoc(items), spineEntries = chapters)
        val started = System.nanoTime()
        val toc = EpubToc.parse(files.keys.toList(), chapters) { files[it] }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(count, toc.size)
        assertEquals(TocEntry("Chapter 2734", count - 1, 0), toc.last())
        assertTrue("took $tookMs ms", tookMs < MAX_PARSE_MS)
    }

    private fun parse(files: Map<String, ByteArray>) = EpubToc.parse(files.keys.toList(), spine) { files[it] }

    private fun navDoc(items: String) =
        """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
           <nav epub:type="toc"><h1>Contents</h1><ol>$items</ol></nav></body></html>"""

    private fun book(
        nav: String? = null,
        ncx: String? = null,
        spineEntries: List<String> = spine,
    ): Map<String, ByteArray> {
        val chapterItems = spineEntries.withIndex().joinToString("") { (i, entry) ->
            "<item id=\"c$i\" href=\"${entry.removePrefix("OEBPS/")}\" media-type=\"application/xhtml+xml\"/>"
        }
        val navItem = if (nav == null) "" else NAV_ITEM
        val ncxItem = if (ncx == null) "" else NCX_ITEM
        val itemrefs = spineEntries.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
        val opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <manifest>$navItem$ncxItem$chapterItems</manifest>
            <spine${if (ncx != null) " toc=\"ncx\"" else ""}>$itemrefs</spine></package>"""
        val container = """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
        return buildMap {
            put("META-INF/container.xml", container.toByteArray())
            put("OEBPS/content.opf", opf.toByteArray())
            nav?.let { put("OEBPS/nav.xhtml", it.toByteArray()) }
            ncx?.let { put("OEBPS/toc.ncx", it.toByteArray()) }
            spineEntries.forEach { put(it, "<html/>".toByteArray()) }
        }
    }

    private companion object {
        const val NAV_ITEM =
            "<item id=\"nav\" href=\"nav.xhtml\" properties=\"nav\" media-type=\"application/xhtml+xml\"/>"
        const val NCX_ITEM = "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"

        /** Generous for a slow CI runner; locally this is tens of milliseconds. */
        const val MAX_PARSE_MS = 2_000L
    }
}
