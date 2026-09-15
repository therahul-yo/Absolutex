package com.absolutex.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ComicInfo.xml comes from inside an untrusted archive, compressed. Without a bound, a small
 * deflate entry expanding into a gigabyte of elements OOMs the DOM builder — an Error, which
 * escapes the parser's "never throw, return null" contract.
 */
class ComicInfoSizeBoundTest {

    private fun bomb(bytes: Int): String = buildString {
        append("<ComicInfo><Series>x</Series>")
        while (length < bytes) append("<Page Image=\"0\"/>")
        append("</ComicInfo>")
    }

    @Test fun `an oversized document is refused, not parsed`() {
        assertNull(ComicInfoParser.parse(bomb((ComicInfoParser.MAX_BYTES + 1024).toInt())))
    }

    @Test fun `an oversized stream is refused, not parsed`() {
        val huge = bomb((ComicInfoParser.MAX_BYTES + 1024).toInt()).toByteArray()
        assertNull(ComicInfoParser.parse(huge.inputStream()))
    }

    @Test fun `an ordinary document still parses`() {
        val info = ComicInfoParser.parse(
            """<ComicInfo><Series>Absolute Batman</Series><Number>1</Number></ComicInfo>"""
        )
        assertEquals("Absolute Batman", info!!.series)
        assertEquals(1.0, info.number!!.value, 0.0)
    }

    @Test fun `a document just under the cap still parses`() {
        val info = ComicInfoParser.parse(bomb((ComicInfoParser.MAX_BYTES / 2).toInt()))
        assertEquals("x", info!!.series)
    }
}
