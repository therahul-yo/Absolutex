package com.absolutex.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layouts taken from real libraries, every one of which the parser got wrong when these were
 * written. Kept as a table because the failures cluster into a few causes: a folder name being
 * parsed as if it were a filename, a bare number outranking a volume marker, and a four-digit
 * year being taken for an issue.
 */
class FilenameParserRealWorldTest {

    // ---- a trailing number belongs to the series when it comes from a folder ----

    @Test fun `folder series keeps its trailing number`() {
        assertEquals("Spider-Man 2099", FilenameParser.parse("Spider-Man 2099/001.cbz").series)
        assertEquals("Mob Psycho 100", FilenameParser.parse("Mob Psycho 100/001.cbz").series)
        assertEquals("Kaiju No. 8", FilenameParser.parse("Kaiju No. 8/001.cbz").series)
    }

    @Test fun `folder series is taken from the nearest named folder`() {
        val p = FilenameParser.parse("Comics/2000 AD/1234.cbz")
        assertEquals("2000 AD", p.series)
        assertEquals(1234.0, p.issue!!.value, 0.0)
        assertTrue(p.seriesFromFolder)
    }

    // ---- a volume marker means the bare number is part of the series, not an issue ----

    @Test fun `volume only release does not invent an issue from the series name`() {
        val p = FilenameParser.parse("Kaiju No. 8 v01 (2021) (Digital) (1r0n).cbz")
        assertEquals("Kaiju No. 8", p.series)
        assertEquals(1, p.volume)
        assertNull("the 8 belongs to the series, not to an issue", p.issue)
        assertEquals(2021, p.year)
    }

    @Test fun `volume only release, no tags`() {
        val p = FilenameParser.parse("Mob Psycho 100 v01.cbz")
        assertEquals("Mob Psycho 100", p.series)
        assertEquals(1, p.volume)
        assertNull(p.issue)
    }

    @Test fun `an explicit issue still wins alongside a volume`() {
        val p = FilenameParser.parse("Batman v02 #005.cbz")
        assertEquals("Batman", p.series)
        assertEquals(2, p.volume)
        assertEquals(5.0, p.issue!!.value, 0.0)
    }

    // ---- a zero-padded leading number is an issue, and the series comes from the folder ----

    @Test fun `zero padded leading number falls back to the folder for the series`() {
        val p = FilenameParser.parse("Attack on Titan/001 To You, 2000 Years From Now.cbz")
        assertEquals("Attack on Titan", p.series)
        assertEquals(1.0, p.issue!!.value, 0.0)
        assertTrue(p.seriesFromFolder)
        assertEquals("To You, 2000 Years From Now", p.title)
    }

    @Test fun `leading number with a trailing part marker still uses the folder`() {
        val p = FilenameParser.parse("Absolute Batman/001 The Zoo Part 1.cbr")
        assertEquals("Absolute Batman", p.series)
        assertEquals(1.0, p.issue!!.value, 0.0)
    }

    @Test fun `an unpadded leading number that opens a series name is not an issue`() {
        // Counterpart to the case above: "2000 AD 1234" must not become issue 2000.
        val p = FilenameParser.parse("2000 AD 1234.cbz")
        assertEquals("2000 AD", p.series)
        assertEquals(1234.0, p.issue!!.value, 0.0)
    }

    // ---- ranges ----

    @Test fun `an issue range does not put its first number in the series`() {
        val p = FilenameParser.parse("Batman 001-012 (2024).cbz")
        assertEquals("Batman", p.series)
        assertEquals(1.0, p.issue!!.value, 0.0)
        assertEquals(2024, p.year)
    }

    @Test fun `unpadded range`() {
        val p = FilenameParser.parse("Batman 1-12.cbz")
        assertEquals("Batman", p.series)
        assertEquals(1.0, p.issue!!.value, 0.0)
    }

    // ---- scene-style dotted names, where the year is not bracketed ----

    @Test fun `an unbracketed year is a year, not an issue`() {
        val p = FilenameParser.parse("Absolute.Batman.001.2024.Webrip.cbr")
        assertEquals("Absolute Batman", p.series)
        assertEquals(1.0, p.issue!!.value, 0.0)
        assertEquals(2024, p.year)
    }

    @Test fun `dotted name with year and no extra words`() {
        val p = FilenameParser.parse("Batman.001.2016.cbr")
        assertEquals("Batman", p.series)
        assertEquals(1.0, p.issue!!.value, 0.0)
        assertEquals(2016, p.year)
    }

    @Test fun `a lone four digit number is still the issue`() {
        // Nothing else to be the issue, so 1234 stays one rather than becoming a year.
        assertEquals(1234.0, FilenameParser.parse("Batman 1234.cbz").issue!!.value, 0.0)
    }

    // ---- display ----

    @Test fun `a fractional issue keeps its leading zero`() {
        assertEquals("Invincible #0.5", FilenameParser.parse("Invincible 0.5.cbz").displayName)
    }

    @Test fun `padding is still stripped for whole issues`() {
        assertEquals("Batman #1", FilenameParser.parse("Batman 001.cbz").displayName)
    }

    // ---- cases that already worked and must keep working ----

    @Test fun `prefix words inside series names are not issue markers`() {
        assertEquals("Chapterhouse", FilenameParser.parse("Chapterhouse 001.cbz").series)
        assertEquals("Volt", FilenameParser.parse("Volt 003.cbz").series)
        assertEquals("The Tome of Doom", FilenameParser.parse("The Tome of Doom 002.cbz").series)
        assertEquals("Part-Time Heroes", FilenameParser.parse("Part-Time Heroes 004.cbz").series)
    }

    @Test fun `the real corpus file still parses exactly`() {
        val p = FilenameParser.parse("Absolute Batman 001 (2024) (Webrip) (The Last Kryptonian-DCP).cbr")
        assertEquals("Absolute Batman", p.series)
        assertEquals(1.0, p.issue!!.value, 0.0)
        assertEquals(2024, p.year)
        assertNull(p.title)
    }
}
