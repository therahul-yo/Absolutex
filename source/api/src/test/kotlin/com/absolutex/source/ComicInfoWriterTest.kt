package com.absolutex.source

import com.absolutex.model.ComicInfo
import com.absolutex.model.ComicInfoPage
import com.absolutex.model.IssueNumber
import com.absolutex.model.PageType
import com.absolutex.model.ReadingFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Writes [info] and reads it back through the real parser — the contract, not the spelling. */
private fun roundTrip(info: ComicInfo): ComicInfo {
    val xml = writeComicInfoXml(info)
    return ComicInfoParser.parse(xml) ?: error("writer produced XML the parser rejected:\n$xml")
}

class ComicInfoWriterTest {

    private val full = ComicInfo(
        series = "Absolute Batman",
        number = IssueNumber.parse("1A"),
        volume = 3,
        title = "Year One",
        writers = listOf("Frank Miller", "David Mazzucchelli"),
        year = 1987,
        pageCount = 4,
        pages = listOf(
            ComicInfoPage(image = 0, type = PageType.FRONT_COVER, widthPx = 1988, heightPx = 3056),
            ComicInfoPage(image = 1, type = PageType.STORY),
            ComicInfoPage(image = 2, type = PageType.STORY, doublePage = true, bookmark = "The fall"),
            ComicInfoPage(image = 3, type = PageType.BACK_COVER),
        ),
        readingFlow = ReadingFlow.RTL,
    )

    @Test
    fun `a fully populated book survives the round trip`() {
        assertEquals(full, roundTrip(full))
    }

    @Test
    fun `an empty book survives the round trip`() {
        assertEquals(ComicInfo(), roundTrip(ComicInfo()))
    }

    /**
     * Absent must stay absent. Writing `<Series/>` for a null series would come back null anyway,
     * so an equality check alone cannot tell the two apart — the document itself is inspected.
     */
    @Test
    fun `absent fields are omitted rather than written empty`() {
        val xml = writeComicInfoXml(ComicInfo(series = "Only This"))
        assertTrue("series should be present", xml.contains("<Series>Only This</Series>"))
        listOf("Number", "Volume", "Title", "Writer", "Year", "PageCount", "Manga", "Pages")
            .forEach { assertTrue("$it must not appear", !xml.contains("<$it")) }
    }

    /** Every page type must name itself in a way the parser maps back to the same constant. */
    @Test
    fun `every page type round trips`() {
        val pages = PageType.entries.mapIndexed { i, type -> ComicInfoPage(image = i, type = type) }
        assertEquals(pages, roundTrip(ComicInfo(pages = pages)).pages)
    }

    /** Both directions ComicInfo can express. VERTICAL is the exception below. */
    @Test
    fun `reading flow round trips for the directions ComicInfo can express`() {
        assertEquals(ReadingFlow.RTL, roundTrip(ComicInfo(readingFlow = ReadingFlow.RTL)).readingFlow)
        assertEquals(ReadingFlow.LTR, roundTrip(ComicInfo(readingFlow = ReadingFlow.LTR)).readingFlow)
    }

    /**
     * A known, deliberate loss: ComicInfo has no vertical direction. Omitting `<Manga>` reports
     * "the file does not say"; writing "No" would claim left-to-right, which is a different book.
     * Pinned so it stays a documented limit of the format.
     */
    @Test
    fun `a vertical book loses its direction rather than claiming left to right`() {
        val xml = writeComicInfoXml(ComicInfo(readingFlow = ReadingFlow.VERTICAL))
        assertTrue("Manga must be omitted entirely", !xml.contains("Manga"))
        assertNull(roundTrip(ComicInfo(readingFlow = ReadingFlow.VERTICAL)).readingFlow)
    }

    /** "1A" and "001" are not the same string as their numeric value, and readers show the string. */
    @Test
    fun `issue numbers keep their raw spelling`() {
        listOf("1A", "001", "10.5").forEach { raw ->
            assertEquals(raw, roundTrip(ComicInfo(number = IssueNumber.parse(raw))).number?.raw)
        }
    }

    /**
     * Markup in metadata must not produce a document our own parser rejects — one odd title would
     * otherwise cost the whole book its metadata.
     */
    @Test
    fun `markup and quotes in metadata survive instead of breaking the document`() {
        val nasty = ComicInfo(
            series = "Hellboy & Co. <B.P.R.D.>",
            title = """She said "hello" & left""",
            pages = listOf(ComicInfoPage(image = 0, bookmark = """a "quoted" & <marked> spot""")),
        )
        assertEquals(nasty, roundTrip(nasty))
    }

    /**
     * Control characters are illegal in XML 1.0 in every form, escaped or not, and metadata that
     * reached an export may have come from a filename. Dropping them keeps the document readable.
     */
    @Test
    fun `characters XML cannot represent are dropped, not escaped into an invalid document`() {
        val withControls = ComicInfo(series = "Bat\u0001man\u0000 \u0007Begins")
        assertEquals("Batman Begins", roundTrip(withControls).series)
    }

    /** A valid surrogate pair is one code point and must not be mistaken for an illegal half. */
    @Test
    fun `astral characters survive`() {
        val info = ComicInfo(title = "Sun 🌞 and Moon")
        assertEquals("Sun 🌞 and Moon", roundTrip(info).title)
    }

    /**
     * A known loss, pinned rather than hidden: ComicInfo separates writers with commas, so a name
     * containing one cannot survive whichever way it is written.
     */
    @Test
    fun `a writer name containing a comma cannot survive ComicInfo`() {
        val split = roundTrip(ComicInfo(writers = listOf("Smith, John"))).writers
        assertEquals(listOf("Smith", "John"), split)
    }

    @Test
    fun `several writers round trip when their names have no commas`() {
        val writers = listOf("Frank Miller", "David Mazzucchelli", "Richmond Lewis")
        assertEquals(writers, roundTrip(ComicInfo(writers = writers)).writers)
    }
}
