package com.absolutex.source

import com.absolutex.model.TitlePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §5.1 wants at least 80 real names in a table; [FilenameParserRealWorldTest] keeps the ones the
 * parser got wrong historically, and this table keeps breadth: every prefix, folder fallbacks,
 * non-ASCII and RTL names, and the adversarial extensions where parsers usually die.
 */
class FilenameParserTableTest {

    private class Row(
        val path: String,
        val series: String?,
        val issue: Double?,
        val volume: Int? = null,
        val year: Int? = null,
        val title: String? = null,
        val seriesFromFolder: Boolean = false,
    )

    private val rows = listOf(
        // ---- the user's own naming, plus close variants ----
        Row("Absolute Batman 001 (2024).cbr", "Absolute Batman", 1.0, year = 2024),
        Row("DC All In Special 001 (2024).cbr", "DC All In Special", 1.0, year = 2024),
        Row("Nightwing 001.cbz", "Nightwing", 1.0),
        Row("Whisper 009.cbz", "Whisper", 9.0),
        Row("Saga #20.cbz", "Saga", 20.0),
        Row("Saga 5.cbz", "Saga", 5.0),
        Row("The Mighty Volt 003.cbz", "The Mighty Volt", 3.0),
        Row("Saga of the Skrulls 010.cbz", "Saga of the Skrulls", 10.0),

        // ---- every issue prefix ----
        Row("Series c7.cbz", "Series", 7.0),
        Row("Series ch 12.cbz", "Series", 12.0),
        Row("Series chap 14.cbz", "Series", 14.0),
        Row("Series chapter 20.cbz", "Series", 20.0),
        Row("Series pt 4.cbz", "Series", 4.0),
        Row("Series part 8.cbz", "Series", 8.0),
        Row("Series issue 30.cbz", "Series", 30.0),
        Row("Series t9.cbz", "Series", 9.0),
        Row("Series tome 11.cbz", "Series", 11.0),
        Row("Series #35.cbz", "Series", 35.0),
        Row("Series Ch.12.cbz", "Series", 12.0),
        Row("Series Chapter.045.cbz", "Series", 45.0),
        Row("Series c.9.cbz", "Series", 9.0),
        Row("Series T.3.cbz", "Series", 3.0),
        Row("Series Tome.7.cbz", "Series", 7.0),
        Row("Series Issue.25.cbz", "Series", 25.0),
        Row("Series Pt.2.cbz", "Series", 2.0),
        Row("Series Part.6.cbz", "Series", 6.0),
        Row("Series Chapter 045 (2021).cbz", "Series", 45.0, year = 2021),

        // ---- volume prefixes: v, vol, volume; alone and beside an explicit issue ----
        Row("Series v.3.cbz", "Series", null, volume = 3),
        Row("Dandadan v04.cbz", "Dandadan", null, volume = 4),
        Row("Sandman vol 5 (2020).cbz", "Sandman", null, volume = 5, year = 2020),
        Row("Series volume 3.cbz", "Series", null, volume = 3),
        Row("Series v02 #005.cbz", "Series", 5.0, volume = 2),
        Row("One Piece v100 #1010.cbz", "One Piece", 1010.0, volume = 100),
        Row("Batman Volume.2 #8.cbz", "Batman", 8.0, volume = 2),
        // With a volume marker present, a bare leftover number belongs to the series (the
        // Kaiju No. 8 rule in resolveNumbers' KDoc). Consequence worth a decision someday:
        // "Series 002 v01" and "Series 003 v01" parse as two series, splitting a shelf.
        Row("Series 002 v01.cbz", "Series 002", null, volume = 1),
        Row("Series v01 002.cbz", "Series", null, volume = 1, title = "002"),
        Row("Series_002_v01.cbz", "Series 002", null, volume = 1),
        Row("Batman Vol. 1", "Batman", null, volume = 1),
        Row("Series v2.cbz", "Series", null, volume = 2),

        // ---- folder fallback: the filename opens with its number ----
        Row("Series/003 - Night Harvest.cbz", "Series", 3.0, title = "Night Harvest", seriesFromFolder = true),
        Row("Series/005.cbz", "Series", 5.0, seriesFromFolder = true),
        Row("Batman/Year One/001.cbz", "Year One", 1.0, seriesFromFolder = true),
        Row("Comics/Series v03/012.cbz", "Series", 12.0, volume = 3, seriesFromFolder = true),
        Row(
            "Comics/Attack on Titan/001 To You, 2000 Years From Now.cbz",
            "Attack on Titan", 1.0, title = "To You, 2000 Years From Now", seriesFromFolder = true,
        ),
        Row("Comics/Sandman (2020)/012.cbz", "Sandman", 12.0, year = 2020, seriesFromFolder = true),
        // The nearest named folder wins, even when an ancestor looks like a decade.
        Row("Comics/2010s/009.cbz", "2010s", 9.0, seriesFromFolder = true),

        // ---- non-ASCII, RTL and CJK ----
        Row("Скиталец 001.cbz", "Скиталец", 1.0),
        Row("Сага #10.cbz", "Сага", 10.0),
        Row("Hélène 002.cbz", "Hélène", 2.0),
        Row("第01巻.cbz", "第01巻", null),
        Row("一拳超人 v01.cbz", "一拳超人", null, volume = 1),
        Row("呪術廻戦 014.cbz", "呪術廻戦", 14.0),
        Row("ספרים 004.cbz", "ספרים", 4.0),
        Row("مانجا 007.cbz", "مانجا", 7.0),
        // ASCII-only digits on purpose: Arabic-Indic digits are never an issue marker.
        Row("Series ٠٠١.cbz", "Series ٠٠١", null),

        // ---- adversarial: numbers competing with the series name ----
        Row("Ultimates 2 007.cbz", "Ultimates 2", 7.0),
        Row("Batman 66 001.cbz", "Batman 66", 1.0),
        Row("2016 Series 004.cbz", "2016 Series", 4.0),
        // The year wins the trailing digits, leaving nothing to shelve the book under.
        Row("2000 AD 2024.cbz", null, 2000.0, year = 2024),
        Row("Series 100 #1900.cbz", "Series 100", 1900.0),
        Row("Spawn 300-301.cbz", "Spawn", 300.0),
        Row("Locke (2011-2015) 020.cbz", "Locke", 20.0, year = 2011),
        Row("Rage (2011-present) 005.cbz", "Rage", 5.0, year = 2011),
        Row("Series 003 (of 6).cbz", "Series", 3.0),
        Row("Series 12.345.cbz", "Series", 12.345),
        Row("Series 12345.cbz", "Series", 12345.0),
        Row("0.5.cbz", null, 0.5),
        Row("Batman #1000 Annual.cbz", "Batman", 1000.0, title = "Annual"),
        Row("Invincible 10.5.cbz", "Invincible", 10.5),

        // ---- scene-style dots and separator debris ----
        // A dotted scene name's trailing word has no marker, so it lands in the title.
        Row("Series.001.2024.Webrip.cbr", "Series", 1.0, year = 2024, title = "Webrip"),
        Row("Series_001_(2024).cbz", "Series", 1.0, year = 2024),
        Row("Series_002_v01.cbz", "Series 002", null, volume = 1),
        // Bracketed scene tags are dropped whole, never kept as words.
        Row("Series.(Digital).(Zone-Empire).cbz", "Series", null),
        // A dot between digits is a fraction, never a separator.
        Row("Series.10.5.cbz", "Series", 10.5),
        // A truncated bracket group is dropped, never left in the series.
        Row("Series 001 (Incomplete.cbz", "Series", 1.0),
        // A mid-name '(' that does not start a word loses only its word-start anchoring.
        Row("Bat(man 001.cbz", "Bat(man", 1.0),

        // ---- extensions must not eat the title tail ----
        Row("Batman.cbz.cbz", "Batman cbz", null),
        Row("Saga.TPB.cbz", "Saga TPB", null),
        Row("Batman 001.epub", "Batman", 1.0),
        Row("Series 002 (2024).CBZ", "Series", 2.0, year = 2024),
        // Deeply nested: the nearest named folder is the series, ancestors only feed volume/year.
        Row(
            "Comics/Saga v05/020 - Chapter Four.cbz",
            "Saga", 20.0, volume = 5, title = "Chapter Four", seriesFromFolder = true,
        ),
        Row("Downloads/Kaiju No. 8 v02/003.cbz", "Kaiju No. 8", 3.0, volume = 2, seriesFromFolder = true),
        Row("Library/Mob Psycho 100/099.5.cbz", "Mob Psycho 100", 99.5, seriesFromFolder = true),
        // The folder keeps its trailing number (folderSeries never strips one), and the
        // ancestor's bracketed year is picked up on the walk.
        Row(
            "Comics/Dirk Gently's Holistic Detective Agency 004 (2017)/001.cbz",
            "Dirk Gently's Holistic Detective Agency 004", 1.0, year = 2017, seriesFromFolder = true,
        ),
        Row("Root/Whisper/Whisper 002.cbz", "Whisper", 2.0),
    )

    @Test fun `every row parses exactly`() {
        rows.forEachIndexed { index, row ->
            val p = FilenameParser.parse(row.path)
            val where = "row ${index + 1}: ${row.path}"
            assertEquals(where, row.series, p.series)
            assertEquals(where, row.issue, p.issue?.value)
            assertEquals(where, row.volume, p.volume)
            assertEquals(where, row.year, p.year)
            assertEquals(where, row.title, p.title)
            assertEquals(where, row.seriesFromFolder, p.seriesFromFolder)
        }
    }

    @Test fun `fractional padding survives into the display name`() {
        assertEquals("Spawn #0.5", FilenameParser.parse("Spawn 0.5.cbz").displayName)
        assertEquals("Spawn #5", FilenameParser.parse("Spawn 005.cbz").displayName)
        assertEquals("Invincible #10.5", FilenameParser.parse("Invincible 10.5.cbz").displayName)
    }

    @Test fun `original filename policy skips the parse entirely`() {
        val bare = FilenameParser.parse("Batman 001.cbz", TitlePolicy.ORIGINAL_FILENAME)
        assertEquals("Batman 001", bare.title)
        assertEquals("Batman 001.cbz", bare.originalFilename)
        assertNull(bare.series)
        assertNull(bare.issue)
        assertNull(bare.year)

        val nested = FilenameParser.parse("Comics/Absolute Batman 001 (2024).cbr", TitlePolicy.ORIGINAL_FILENAME)
        assertEquals("Absolute Batman 001 (2024)", nested.title)
        assertEquals("Absolute Batman 001 (2024).cbr", nested.originalFilename)
        assertNull(nested.year)
    }
}
