package com.absolutex.source

import com.absolutex.model.TitlePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameParserTest {

    // ---------------------------------------------------------------- real corpus (§5.1)

    @Test
    fun `parses the Absolute Batman corpus file exactly`() {
        val parsed = FilenameParser.parse(
            "Absolute Batman 001 (2024) (Webrip) (The Last Kryptonian-DCP).cbr"
        )
        val issue = parsed.issue!!
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, issue.value, 0.0)
        assertEquals("001", issue.raw)
        assertEquals(2024, parsed.year)
        assertNull(parsed.volume)
        assertNull("scan tags must not leak into the title", parsed.title)
        assertEquals("Absolute Batman #1", parsed.displayName)
    }

    @Test
    fun `parses the Absolute Wonder Woman corpus file exactly`() {
        val parsed = FilenameParser.parse(
            "Absolute Wonder Woman 001 (2024) (Webrip) (The Last Kryptonian-DCP).cbr"
        )
        assertEquals("Absolute Wonder Woman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertEquals(2024, parsed.year)
        assertNull(parsed.title)
    }

    // ---------------------------------------------------------------- extensions

    @Test
    fun `strips container extensions whatever their case`() {
        for (extension in listOf("cbz", "CBZ", "CbR", "cB7", "cbt", "Zip", "RAR", "7z")) {
            val parsed = FilenameParser.parse("Absolute Batman 001.$extension")
            assertEquals("failed on .$extension", "Absolute Batman", parsed.series)
            assertEquals("failed on .$extension", 1.0, parsed.issue!!.value, 0.0)
        }
    }

    @Test
    fun `strips an unknown extension that still looks like one`() {
        val parsed = FilenameParser.parse("Absolute Batman 001.cbx")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
    }

    @Test
    fun `a numeric tail is a volume number, not an extension`() {
        val parsed = FilenameParser.parse("Batman Vol. 1")
        assertEquals("Batman", parsed.series)
        assertEquals(1, parsed.volume)
        assertNull(parsed.issue)
    }

    @Test
    fun `parses a name with no extension at all`() {
        val parsed = FilenameParser.parse("Absolute Batman 001")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
    }

    // ---------------------------------------------------------------- volume prefixes

    @Test
    fun `recognises every volume prefix form`() {
        val expected = mapOf(
            "Batman v3.cbz" to 3,
            "Batman v03.cbz" to 3,
            "Batman v.3.cbz" to 3,
            "Batman vol 3.cbz" to 3,
            "Batman Vol. 3.cbz" to 3,
            "Batman VOLUME 3.cbz" to 3,
            "Batman Volume 03.cbz" to 3,
        )
        for ((name, volume) in expected) {
            val parsed = FilenameParser.parse(name)
            assertEquals("failed on $name", volume, parsed.volume)
            assertEquals("failed on $name", "Batman", parsed.series)
        }
    }

    @Test
    fun `a volume number is never reused as the issue number`() {
        val parsed = FilenameParser.parse("Batman v02.cbz")
        assertEquals(2, parsed.volume)
        assertNull("v02 supplies the volume only", parsed.issue)
        assertEquals("Batman", parsed.series)
    }

    @Test
    fun `keeps volume and chapter apart in a scene release name`() {
        val parsed = FilenameParser.parse("Saga v01 c001 (2012) (Digital) (Zone-Empire).cbz")
        val issue = parsed.issue!!
        assertEquals("Saga", parsed.series)
        assertEquals(1, parsed.volume)
        assertEquals(1.0, issue.value, 0.0)
        assertEquals("001", issue.raw)
        assertEquals(2012, parsed.year)
    }

    // ---------------------------------------------------------------- issue prefixes

    @Test
    fun `recognises every issue prefix form`() {
        val expected = mapOf(
            "Batman #12.cbz" to 12.0,
            "Batman#5.cbz" to 5.0,
            "Batman c015.cbz" to 15.0,
            "Batman ch 686.cbz" to 686.0,
            "Batman ch. 686.cbz" to 686.0,
            "Batman chap 686.cbz" to 686.0,
            "Batman Chapter 097.cbz" to 97.0,
            "Batman pt 3.cbz" to 3.0,
            "Batman Part 3.cbz" to 3.0,
            "Batman issue 7.cbz" to 7.0,
            "Batman t01.cbz" to 1.0,
            "Batman Tome 4.cbz" to 4.0,
        )
        for ((name, issue) in expected) {
            val parsed = FilenameParser.parse(name)
            assertEquals("failed on $name", issue, parsed.issue?.value ?: -1.0, 0.0)
            assertEquals("failed on $name", "Batman", parsed.series)
        }
    }

    @Test
    fun `keeps a fractional chapter number`() {
        val parsed = FilenameParser.parse("Naruto Chapter 700.5.cbz")
        val issue = parsed.issue!!
        assertEquals("Naruto", parsed.series)
        assertEquals(700.5, issue.value, 0.0)
        assertEquals("700.5", issue.raw)
    }

    @Test
    fun `a dot before a fraction is not a separator when the name has spaces`() {
        val parsed = FilenameParser.parse("Batman 10.5.cbz")
        assertEquals("Batman", parsed.series)
        assertEquals(10.5, parsed.issue!!.value, 0.0)
    }

    @Test
    fun `an issue prefix inside a word does not fire`() {
        // "Chainsaw" opens with "ch" and "Classic" with "c"; neither is a chapter marker.
        assertEquals("Chainsaw Man", FilenameParser.parse("Chainsaw Man Chapter 097.cbz").series)
        assertEquals("Classic Batman", FilenameParser.parse("Classic Batman 001.cbz").series)
        assertEquals("One Piece", FilenameParser.parse("One Piece Tome 4.cbz").series)
    }

    // ---------------------------------------------------------------- folder fallback

    @Test
    fun `falls back to the parent folder when the filename is only a number`() {
        val parsed = FilenameParser.parse("Absolute Batman/001.cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertTrue(parsed.seriesFromFolder)
    }

    @Test
    fun `walks past a volume folder and keeps its volume number`() {
        val parsed = FilenameParser.parse("Comics/DC/Absolute Batman/v01/001.cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1, parsed.volume)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertTrue(parsed.seriesFromFolder)
    }

    @Test
    fun `takes the year off the folder when the filename has none`() {
        val parsed = FilenameParser.parse("Absolute Batman (2024)/001.cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(2024, parsed.year)
    }

    @Test
    fun `a leading number followed by a dash leaves the rest as the title`() {
        val parsed = FilenameParser.parse("Absolute Batman/001 - The Beginning.cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertEquals("The Beginning", parsed.title)
        assertEquals("Absolute Batman #1 - The Beginning", parsed.displayName)
    }

    @Test
    fun `a leading number followed by words leaves the words as the title`() {
        val parsed = FilenameParser.parse("Vinland Saga/001 Something Wicked.cbz")
        assertEquals("Vinland Saga", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertEquals("Something Wicked", parsed.title)
    }

    @Test
    fun `does not repeat the series as the title`() {
        val parsed = FilenameParser.parse("Absolute Batman/001 Absolute Batman.cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertNull(parsed.title)
    }

    @Test
    fun `degrades to no series when a bare number has no folder above it`() {
        val parsed = FilenameParser.parse("001.cbr")
        assertNull(parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertFalse(parsed.seriesFromFolder)
        assertEquals("001.cbr", parsed.displayName)
    }

    @Test
    fun `accepts windows path separators`() {
        val parsed = FilenameParser.parse("Comics\\Absolute Batman\\001.cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
    }

    @Test
    fun `a series that starts with digits is not replaced by its folder`() {
        val parsed = FilenameParser.parse("Comics/2000 AD 1234.cbz")
        assertEquals("2000 AD", parsed.series)
        assertEquals(1234.0, parsed.issue!!.value, 0.0)
        assertFalse("the filename named its own series", parsed.seriesFromFolder)
    }

    @Test
    fun `survives deep nesting with nothing useful above the file`() {
        val parsed = FilenameParser.parse("a/b/c/d/e/f/g/Absolute Batman 001.cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertFalse(parsed.seriesFromFolder)
    }

    // ---------------------------------------------------------------- scan tags

    @Test
    fun `strips square and curly scan tags`() {
        val parsed = FilenameParser.parse("Absolute Batman 001 [digital] {c2c}.cbz")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertNull(parsed.title)
    }

    @Test
    fun `takes the first year of a publication run`() {
        val parsed = FilenameParser.parse("Batman (2011-2015) 005.cbz")
        assertEquals("Batman", parsed.series)
        assertEquals(2011, parsed.year)
        assertEquals(5.0, parsed.issue!!.value, 0.0)
    }

    @Test
    fun `accepts an open-ended publication run`() {
        assertEquals(2016, FilenameParser.parse("Batman (2016-present) 005.cbz").year)
        assertEquals(2016, FilenameParser.parse("Batman (2016-????) 005.cbz").year)
    }

    @Test
    fun `ignores a four digit tag that cannot be a year`() {
        val parsed = FilenameParser.parse("Batman (0500) 005.cbz")
        assertNull(parsed.year)
        assertEquals(5.0, parsed.issue!!.value, 0.0)
    }

    @Test
    fun `drops a bracket group the name was truncated inside`() {
        val parsed = FilenameParser.parse("Absolute Batman 001 (2024.cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertNull("an unterminated tag cannot be trusted for the year", parsed.year)
    }

    @Test
    fun `a stray bracket mid name does not swallow the issue number`() {
        val parsed = FilenameParser.parse("Bat(man 001.cbz")
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertEquals("Bat(man", parsed.series)
    }

    // ---------------------------------------------------------------- separators

    @Test
    fun `rewrites scene release dots as spaces`() {
        val parsed = FilenameParser.parse("Absolute.Batman.001.cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
    }

    @Test
    fun `a dot between digits survives dot-to-space rewriting`() {
        // "Series/10.5.cbz" is a real manga naming pattern. Rewriting every dot would read it as
        // "10 5" and file the side-story as issue 5.
        val parsed = FilenameParser.parse("Vinland Saga/10.5.cbz")
        val issue = parsed.issue!!
        assertEquals("Vinland Saga", parsed.series)
        assertEquals(10.5, issue.value, 0.0)
        assertEquals("10.5", issue.raw)
    }

    @Test
    fun `rewrites the separator dots of a scene name but not a fractional chapter`() {
        val parsed = FilenameParser.parse("Chainsaw.Man.c097.5.cbz")
        assertEquals("Chainsaw Man", parsed.series)
        assertEquals(97.5, parsed.issue!!.value, 0.0)
    }

    @Test
    fun `rewrites separator dots around a volume token`() {
        val parsed = FilenameParser.parse("Batman.Vol.3.cbz")
        assertEquals("Batman", parsed.series)
        assertEquals(3, parsed.volume)
    }

    @Test
    fun `rewrites underscores as spaces`() {
        val parsed = FilenameParser.parse("Absolute_Batman_001_(2024).cbr")
        assertEquals("Absolute Batman", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertEquals(2024, parsed.year)
    }

    // ---------------------------------------------------------------- non-ASCII and RTL

    @Test
    fun `parses a Cyrillic series name`() {
        val parsed = FilenameParser.parse("Тайна Бэтмена 001 (2024).cbr")
        assertEquals("Тайна Бэтмена", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertEquals(2024, parsed.year)
    }

    @Test
    fun `parses a right-to-left series name`() {
        // The series name is Arabic for "Batman". Stored logical-order: BEH ALEF TEH MEEM ALEF NOON,
        // then the space, then "001" — which is what the parser sees, whatever the editor renders.
        val parsed = FilenameParser.parse("باتمان 001 (2024).cbr")
        assertEquals("باتمان", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
        assertEquals(2024, parsed.year)
    }

    @Test
    fun `parses a right-to-left series name from its folder`() {
        val parsed = FilenameParser.parse("باتمان/001.cbr")
        assertEquals("باتمان", parsed.series)
        assertTrue(parsed.seriesFromFolder)
    }

    @Test
    fun `parses a CJK series name`() {
        val parsed = FilenameParser.parse("鋼の錬金術師 001.cbz")
        assertEquals("鋼の錬金術師", parsed.series)
        assertEquals(1.0, parsed.issue!!.value, 0.0)
    }

    @Test
    fun `does not guess an issue out of a CJK volume marker`() {
        // "第01巻" is a volume marker we do not claim to understand. Reporting no issue is correct;
        // reporting chapter 1 would silently mis-shelve the book.
        val name = "とある魔術の禁書目録 第01巻.cbz"
        val parsed = FilenameParser.parse(name)
        assertNull(parsed.issue)
        assertEquals("とある魔術の禁書目録 第01巻", parsed.series)
    }

    // ---------------------------------------------------------------- escape hatch

    @Test
    fun `the original filename policy reports the filename and nothing else`() {
        val parsed = FilenameParser.parse(
            "Comics/Absolute Batman 001 (2024) (Webrip).cbr",
            TitlePolicy.ORIGINAL_FILENAME,
        )
        assertEquals("Absolute Batman 001 (2024) (Webrip)", parsed.title)
        assertEquals("Absolute Batman 001 (2024) (Webrip).cbr", parsed.originalFilename)
        assertEquals("Absolute Batman 001 (2024) (Webrip)", parsed.displayName)
        assertNull("a disabled heuristic must not leak back in through sorting", parsed.series)
        assertNull(parsed.issue)
        assertNull(parsed.volume)
        assertNull(parsed.year)
        assertFalse(parsed.seriesFromFolder)
    }

    @Test
    fun `the parsed policy is the default`() {
        assertEquals(
            FilenameParser.parse("Absolute Batman 001.cbr", TitlePolicy.PARSED),
            FilenameParser.parse("Absolute Batman 001.cbr"),
        )
    }

    // ---------------------------------------------------------------- missing fields, junk

    @Test
    fun `keeps a hyphenated name whole when there is no number to split on`() {
        // Without a number there is no way to tell "Series - Title" from a series whose name
        // contains a dash, so we keep it whole rather than invent a wrong series.
        val parsed = FilenameParser.parse("Batman - The Long Halloween.cbz")
        assertEquals("Batman - The Long Halloween", parsed.series)
        assertNull(parsed.issue)
        assertNull(parsed.title)
    }

    @Test
    fun `reports nothing for a name made only of separators`() {
        val parsed = FilenameParser.parse("___.cbz")
        assertNull(parsed.series)
        assertNull(parsed.issue)
        assertEquals("___.cbz", parsed.displayName)
    }

    @Test
    fun `handles an empty path without throwing`() {
        val parsed = FilenameParser.parse("")
        assertNull(parsed.series)
        assertNull(parsed.issue)
        assertEquals("", parsed.originalFilename)
    }

    @Test
    fun `handles a path that is only separators`() {
        val parsed = FilenameParser.parse("///")
        assertNull(parsed.series)
        assertNull(parsed.issue)
    }

    @Test
    fun `builds a display name from every field it has`() {
        val parsed = FilenameParser.parse("Batman v01 c005 - Year One.cbz")
        assertEquals("Batman", parsed.series)
        assertEquals(1, parsed.volume)
        assertEquals(5.0, parsed.issue!!.value, 0.0)
        assertEquals("Year One", parsed.title)
        assertEquals("Batman Vol. 1 #5 - Year One", parsed.displayName)
    }
}
