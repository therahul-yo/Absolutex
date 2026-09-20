package com.absolutex.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ParsedName.displayParts] — the seam that lets a UI layer build a book's label from a
 * localised format string instead of the English this module bakes into [ParsedName.displayName].
 *
 * The load-bearing test is [parts rejoin into exactly displayName]. The two have to agree for
 * every input or the library shows one label and the logs another, and the failure would be
 * quiet: both look plausible in isolation.
 */
class DisplayPartsTest {

    /**
     * Join the parts the way [ParsedName.displayName] does. This is the English formatter a UI
     * layer replaces with a resource; here it exists to prove the parts carry everything the
     * label needs, with nothing left behind in the glue.
     */
    private fun DisplayParts.rejoin(): String {
        if (isFilenameOnly) return originalFilename
        return buildString {
            if (series != null) append(series)
            if (volume != null) {
                if (isNotEmpty()) append(' ')
                append("Vol. ").append(volume)
            }
            if (issue != null) {
                if (isNotEmpty()) append(' ')
                append('#').append(issue)
            }
            if (title != null) {
                if (isNotEmpty()) append(" - ")
                append(title)
            }
        }
    }

    private fun parsed(
        series: String? = null,
        issue: String? = null,
        volume: Int? = null,
        title: String? = null,
        filename: String = "file.cbz",
    ) = ParsedName(
        series = series,
        issue = issue?.let { IssueNumber.parse(it) },
        volume = volume,
        title = title,
        originalFilename = filename,
    )

    @Test
    fun `carries every piece of a full label`() {
        val parts = parsed("Batman", issue = "5", volume = 1, title = "Year One").displayParts
        assertEquals("Batman", parts.series)
        assertEquals(1, parts.volume)
        assertEquals("5", parts.issue)
        assertEquals("Year One", parts.title)
        assertTrue(!parts.isFilenameOnly)
    }

    @Test
    fun `issue keeps the scanner's spelling minus its padding`() {
        assertEquals("1", parsed("S", issue = "001").displayParts.issue)
        assertEquals("10.5", parsed("S", issue = "10.5").displayParts.issue)
        assertEquals("1A", parsed("S", issue = "1A").displayParts.issue)
        // Trimming zeros must not eat the one that makes a fraction readable.
        assertEquals("0.5", parsed("S", issue = "0.5").displayParts.issue)
    }

    @Test
    fun `title that only repeats the series is dropped`() {
        assertNull(parsed("Batman", title = "Batman").displayParts.title)
    }

    @Test
    fun `a book with no parsed identity is filename only`() {
        val parts = parsed(issue = "1", filename = "001.cbr").displayParts
        assertTrue("numbers alone do not identify a book", parts.isFilenameOnly)
        assertEquals("001.cbr", parts.originalFilename)
        assertNull(parts.series)
        // The issue is dropped rather than offered, so a caller cannot render a bare "#1".
        assertNull(parts.issue)
    }

    @Test
    fun `parts rejoin into exactly displayName`() {
        val cases = listOf(
            parsed("Absolute Batman", issue = "001", filename = "Absolute Batman 001.cbr"),
            parsed("Batman", issue = "5", volume = 1, title = "Year One"),
            parsed("Batman", volume = 3),
            parsed("Batman", title = "Batman"),
            parsed("Spawn", issue = "0.5"),
            parsed("Invincible", issue = "10.5"),
            parsed(title = "Untitled"),
            parsed(issue = "1", filename = "001.cbr"),
            parsed(filename = "___.cbz"),
            // Degenerate inputs: a blank series, and one that composes to nothing at all.
            parsed("", filename = "blank.cbz"),
            parsed("", issue = "1", filename = "blank-issue.cbz"),
        )
        for (case in cases) {
            assertEquals(
                "parts must rejoin into displayName for $case",
                case.displayName,
                case.displayParts.rejoin(),
            )
        }
    }
}
