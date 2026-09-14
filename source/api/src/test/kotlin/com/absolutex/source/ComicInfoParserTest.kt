package com.absolutex.source

import com.absolutex.model.PageType
import com.absolutex.model.ReadingFlow
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicInfoParserTest {

    // ---------------------------------------------------------------- happy paths

    @Test
    fun `reads every field we act on`() {
        val info = ComicInfoParser.parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <Series>Absolute Batman</Series>
              <Number>1</Number>
              <Volume>2024</Volume>
              <Title>The Zoo</Title>
              <Writer>Scott Snyder</Writer>
              <Year>2024</Year>
              <PageCount>32</PageCount>
              <Manga>No</Manga>
            </ComicInfo>
            """.trimIndent()
        )!!
        assertEquals("Absolute Batman", info.series)
        assertEquals(1.0, info.number!!.value, 0.0)
        assertEquals(2024, info.volume)
        assertEquals("The Zoo", info.title)
        assertEquals(listOf("Scott Snyder"), info.writers)
        assertEquals(2024, info.year)
        assertEquals(32, info.pageCount)
        assertEquals(ReadingFlow.LTR, info.readingFlow)
    }

    @Test
    fun `an empty ComicInfo yields a fully absent record rather than a failure`() {
        val info = ComicInfoParser.parse("<ComicInfo/>")!!
        assertNull(info.series)
        assertNull(info.number)
        assertNull(info.volume)
        assertNull(info.title)
        assertNull(info.year)
        assertNull(info.pageCount)
        assertNull(info.readingFlow)
        assertTrue(info.writers.isEmpty())
        assertTrue(info.pages.isEmpty())
    }

    @Test
    fun `accepts lowercase element and attribute names`() {
        val info = ComicInfoParser.parse(
            """
            <comicinfo>
              <series>Saga</series>
              <number>15</number>
              <pages><page image="0" type="frontcover" doublepage="true"/></pages>
            </comicinfo>
            """.trimIndent()
        )!!
        assertEquals("Saga", info.series)
        assertEquals(15.0, info.number!!.value, 0.0)
        assertEquals(PageType.FRONT_COVER, info.pages.single().type)
        assertTrue(info.pages.single().doublePage)
    }

    @Test
    fun `keeps a letter-suffixed issue number`() {
        val number = ComicInfoParser.parse("<ComicInfo><Number>1A</Number></ComicInfo>")!!.number!!
        assertEquals(1.0, number.value, 0.0)
        assertEquals("1A", number.raw)
    }

    @Test
    fun `collects writers from repeated and comma separated elements`() {
        val info = ComicInfoParser.parse(
            """
            <ComicInfo>
              <Writer>Scott Snyder, Nick Dragotta</Writer>
              <Writer>Nick Dragotta</Writer>
            </ComicInfo>
            """.trimIndent()
        )!!
        assertEquals(listOf("Scott Snyder", "Nick Dragotta"), info.writers)
    }

    // ---------------------------------------------------------------- ComicRack quirks

    @Test
    fun `treats the ComicRack minus one sentinel as an absent value`() {
        val info = ComicInfoParser.parse(
            """
            <ComicInfo>
              <Series>Batman</Series>
              <Volume>-1</Volume>
              <Year>-1</Year>
              <PageCount>-1</PageCount>
              <Number>-1</Number>
            </ComicInfo>
            """.trimIndent()
        )!!
        assertEquals("Batman", info.series)
        assertNull(info.volume)
        assertNull(info.year)
        assertNull(info.pageCount)
        assertNull(info.number)
    }

    @Test
    fun `ignores a year outside any plausible publication range`() {
        assertNull(ComicInfoParser.parse("<ComicInfo><Year>500</Year></ComicInfo>")!!.year)
        assertEquals(1939, ComicInfoParser.parse("<ComicInfo><Year>1939</Year></ComicInfo>")!!.year)
    }

    @Test
    fun `maps every Manga value to a reading flow`() {
        fun flow(value: String) =
            ComicInfoParser.parse("<ComicInfo><Manga>" + value + "</Manga></ComicInfo>")!!.readingFlow
        assertEquals(ReadingFlow.RTL, flow("YesAndRightToLeft"))
        assertEquals(ReadingFlow.RTL, flow("yesandrighttoleft"))
        assertEquals(ReadingFlow.RTL, flow("Yes"))
        assertEquals(ReadingFlow.LTR, flow("No"))
        assertNull(flow("Unknown"))
        assertNull(ComicInfoParser.parse("<ComicInfo/>")!!.readingFlow)
    }

    @Test
    fun `honours the encoding declaration on the stream overload`() {
        // ComicRack wrote windows-1252, so the byte path has to keep the declaration and with it
        // the accented characters that depend on it.
        val xml = "<?xml version=\"1.0\" encoding=\"windows-1252\"?>" +
            "<ComicInfo><Writer>René Goscinny</Writer></ComicInfo>"
        val bytes = xml.toByteArray(charset("windows-1252"))
        val info = ComicInfoParser.parse(ByteArrayInputStream(bytes))!!
        assertEquals(listOf("René Goscinny"), info.writers)
    }

    @Test
    fun `ignores the encoding declaration on the string overload`() {
        // The bytes are already decoded by the time a String arrives, so a stale declaration in the
        // text must not be applied to them a second time.
        val xml = "<?xml version=\"1.0\" encoding=\"windows-1252\"?>" +
            "<ComicInfo><Writer>René Goscinny</Writer></ComicInfo>"
        assertEquals(listOf("René Goscinny"), ComicInfoParser.parse(xml)!!.writers)
    }

    // ---------------------------------------------------------------- pages

    @Test
    fun `reads page types, double-page flags and dimensions`() {
        val info = ComicInfoParser.parse(
            """
            <ComicInfo>
              <Pages>
                <Page Image="0" Type="FrontCover" ImageWidth="1988" ImageHeight="3056"/>
                <Page Image="1"/>
                <Page Image="2" DoublePage="true" Bookmark="Spread"/>
                <Page Image="3" DoublePage="1"/>
                <Page Image="4" DoublePage="false"/>
                <Page Image="5" Type="Advertisement"/>
                <Page Image="6" Type="BackCover"/>
                <Page Image="7" Type="SomethingNew"/>
              </Pages>
            </ComicInfo>
            """.trimIndent()
        )!!
        assertEquals(8, info.pages.size)
        assertEquals(PageType.FRONT_COVER, info.pages[0].type)
        assertEquals(1988, info.pages[0].widthPx)
        assertEquals(3056, info.pages[0].heightPx)
        assertEquals("a page with no Type is a story page", PageType.STORY, info.pages[1].type)
        assertNull(info.pages[1].widthPx)
        assertTrue(info.pages[2].doublePage)
        assertEquals("Spread", info.pages[2].bookmark)
        assertTrue("ComicRack also writes DoublePage=1", info.pages[3].doublePage)
        assertFalse(info.pages[4].doublePage)
        assertEquals(PageType.ADVERTISEMENT, info.pages[5].type)
        assertEquals(PageType.BACK_COVER, info.pages[6].type)
        assertEquals("an unknown type must not fail the parse", PageType.OTHER, info.pages[7].type)
    }

    @Test
    fun `drops pages with no usable image index`() {
        val info = ComicInfoParser.parse(
            """
            <ComicInfo>
              <Pages>
                <Page Type="Story"/>
                <Page Image="" Type="Story"/>
                <Page Image="notanumber"/>
                <Page Image="-1"/>
                <Page Image="3"/>
              </Pages>
            </ComicInfo>
            """.trimIndent()
        )!!
        assertEquals(listOf(3), info.pages.map { it.image })
    }

    @Test
    fun `indexes pages by image number`() {
        val info = ComicInfoParser.parse(
            """
            <ComicInfo>
              <Pages>
                <Page Image="7" DoublePage="true"/>
                <Page Image="0" Type="FrontCover"/>
              </Pages>
            </ComicInfo>
            """.trimIndent()
        )!!
        assertEquals(PageType.FRONT_COVER, info.pagesByImage[0]!!.type)
        assertTrue(info.pagesByImage[7]!!.doublePage)
        assertNull(info.pagesByImage[1])
    }

    @Test
    fun `ignores comments and non-Page children of Pages`() {
        val info = ComicInfoParser.parse(
            "<ComicInfo><Pages><!-- a comment --><Note/><Page Image=\"0\"/></Pages></ComicInfo>"
        )!!
        assertEquals(1, info.pages.size)
        assertEquals(0, info.pages.single().image)
    }

    // ---------------------------------------------------------------- malformed input

    @Test
    fun `unclosed element returns null`() {
        assertNull(ComicInfoParser.parse("<ComicInfo><Series>Batman</ComicInfo>"))
    }

    @Test
    fun `truncated document returns null`() {
        assertNull(ComicInfoParser.parse("<ComicInfo><Series>Bat"))
    }

    @Test
    fun `empty and blank input returns null`() {
        assertNull(ComicInfoParser.parse(""))
        assertNull(ComicInfoParser.parse("   \n  "))
    }

    @Test
    fun `non-XML input returns null`() {
        assertNull(ComicInfoParser.parse("this is not xml at all"))
        assertNull(ComicInfoParser.parse("  binary junk"))
    }

    @Test
    fun `a document that is not ComicInfo returns null`() {
        assertNull(ComicInfoParser.parse("<Foo><Series>Batman</Series></Foo>"))
        assertNull(ComicInfoParser.parse("<?xml version=\"1.0\"?><rss><channel/></rss>"))
    }

    @Test
    fun `mismatched closing tag inside Pages returns null`() {
        assertNull(ComicInfoParser.parse("<ComicInfo><Pages><Page Image=\"0\"></Pages></ComicInfo>"))
    }

    @Test
    fun `an unquoted attribute returns null`() {
        assertNull(ComicInfoParser.parse("<ComicInfo><Pages><Page Image=0/></Pages></ComicInfo>"))
    }

    // ---------------------------------------------------------------- hostile input

    @Test
    fun `rejects a document with a DOCTYPE`() {
        // The archive is untrusted, so DTDs are refused outright; that also shuts off every
        // entity-expansion and external-reference trick below.
        assertNull(
            ComicInfoParser.parse(
                """
                <!DOCTYPE ComicInfo [<!ENTITY who "Batman">]>
                <ComicInfo><Series>&who;</Series></ComicInfo>
                """.trimIndent()
            )
        )
    }

    @Test
    fun `does not expand an entity bomb`() {
        val bomb = buildString {
            append("<!DOCTYPE lolz [<!ENTITY lol \"lol\">")
            append("<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">")
            append("<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">")
            append("<!ENTITY lol4 \"&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;\">]>")
            append("<ComicInfo><Series>&lol4;</Series></ComicInfo>")
        }
        assertNull(ComicInfoParser.parse(bomb))
    }

    @Test
    fun `does not read an external entity off the filesystem`() {
        assertNull(
            ComicInfoParser.parse(
                """
                <!DOCTYPE ComicInfo [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <ComicInfo><Series>&secret;</Series></ComicInfo>
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a deeply nested document never throws`() {
        val depth = 5_000
        val deep = buildString {
            append("<ComicInfo>")
            repeat(depth) { append("<a>") }
            repeat(depth) { append("</a>") }
            append("</ComicInfo>")
        }
        // Either outcome is acceptable; the contract is that it returns rather than throws.
        assertNull(ComicInfoParser.parse(deep)?.series)
    }

    @Test
    fun `a huge page list never throws`() {
        val pages = (0 until 20_000).joinToString("") { "<Page Image=\"" + it + "\"/>" }
        val info = ComicInfoParser.parse("<ComicInfo><Pages>" + pages + "</Pages></ComicInfo>")!!
        assertEquals(20_000, info.pages.size)
        assertEquals(19_999, info.pagesByImage[19_999]!!.image)
    }
}
