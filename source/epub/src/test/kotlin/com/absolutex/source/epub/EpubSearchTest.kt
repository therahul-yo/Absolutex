package com.absolutex.source.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-book search: what counts as a chapter's text, how a query matches, and what a hit shows. */
class EpubSearchTest {

    private fun search(html: String, query: String, limit: Int = 100) =
        EpubSearch.matches(EpubSearch.textOf(html.toByteArray()), EpubSearch.pattern(query)!!, limit)

    @Test fun `matches ignore case and count occurrences from zero`() {
        val hits = search("<body><p>The Void. the void! THE VOID?</p></body>", "the void")
        assertEquals(listOf(0, 1, 2), hits.map { it.occurrence })
    }

    @Test fun `unicode letters fold case like the page script does`() {
        assertEquals(1, search("<body><p>ÉCOLE</p></body>", "école").size)
    }

    @Test fun `words match across any whitespace but never across a tag`() {
        assertEquals(1, search("<body><p>Sunny\n   Kingdom</p></body>", "sunny kingdom").size)
        // A DOM text-node walk cannot see "Sunny Kingdom" here either: the counts must agree.
        assertEquals(0, search("<body><p>Sunny <b>Kingdom</b></p></body>", "sunny kingdom").size)
    }

    @Test fun `head, scripts, styles and comments are not text`() {
        val html = """<html><head><title>Void</title><style>.void{}</style></head>
            <body><!-- void --><script>var v = "void"</script><p>one void</p></body></html>"""
        assertEquals(1, search(html, "void").size)
    }

    @Test fun `entities are decoded before matching`() {
        assertEquals(1, search("<body><p>Rock &amp; Roll &#8212; &#x2014;</p></body>", "rock & roll").size)
        assertEquals(1, search("<body><p>no&nbsp;break</p></body>", "no break").size)
    }

    @Test fun `regex syntax in a query is literal`() {
        assertEquals(1, search("<body><p>a+b (c) [d] 3.14 x|y</p></body>", "a+b (c) [d] 3.14 x|y").size)
        assertEquals(0, search("<body><p>aab</p></body>", "a.b").size)
    }

    @Test fun `a snippet reads as prose and marks the match`() {
        val hit = search("<body><p>Before the <i>long</i> wait came the Void itself.</p></body>", "void").single()
        assertEquals("Void", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertTrue(hit.snippet, hit.snippet.contains("the long wait came the Void itself."))
    }

    @Test fun `long context is cut with ellipses`() {
        val text = "x".repeat(200) + " needle " + "y".repeat(200)
        val hit = search("<body><p>$text</p></body>", "needle").single()
        assertTrue(hit.snippet.startsWith("…") && hit.snippet.endsWith("…"))
        assertEquals("needle", hit.snippet.substring(hit.matchStart, hit.matchEnd))
    }

    @Test fun `the limit caps the hits returned`() {
        assertEquals(3, search("<body><p>" + "ab ".repeat(50) + "</p></body>", "ab", limit = 3).size)
    }

    @Test fun `too short or blank queries are not searched`() {
        assertNull(EpubSearch.pattern(" "))
        assertNull(EpubSearch.pattern("a"))
    }

    @Test fun `a 2,700 chapter novel searches quickly`() {
        val chapter = ("<body>" + "<p>Sunny walked through the dark and quiet shadows of the city.</p>".repeat(60) +
            "<p>The Spell whispered.</p></body>").toByteArray()
        val pattern = EpubSearch.pattern("spell whispered")!!
        val started = System.nanoTime()
        var hits = 0
        repeat(CHAPTERS) { hits += EpubSearch.matches(EpubSearch.textOf(chapter), pattern, 10).size }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(CHAPTERS, hits)
        assertTrue("took $tookMs ms", tookMs < MAX_SEARCH_MS)
    }

    private companion object {
        const val CHAPTERS = 2_734

        /** Generous for a slow CI runner. */
        const val MAX_SEARCH_MS = 10_000L
    }
}
