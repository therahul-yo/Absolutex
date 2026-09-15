package com.absolutex.source

import com.absolutex.model.IssueNumber
import com.absolutex.model.ParsedName
import com.absolutex.model.TitlePolicy

/**
 * Recovers series / volume / issue / year / title from a container path (§5.1).
 *
 * This is a heuristic over conventions we do not control, so it is built to fail quietly: every
 * field is optional and an unrecognised name comes back as a series and nothing else. The one
 * thing it must never do is invent a wrong series, because that silently splits a shelf in two.
 *
 * All regexes below use ASCII digit classes on purpose. `\p{L}` covers every script for the word
 * boundaries — so Cyrillic, Arabic and CJK series names parse — but issue numbers are matched as
 * ASCII only: no scanner writes Arabic-Indic digits in a filename, and accepting them would make
 * "第01巻" parse as a chapter marker it is not.
 */
object FilenameParser {

    /** Plausible publication years; outside this a four-digit number is not a year. */
    private val PLAUSIBLE_YEARS = 1900..2199
    private const val YEAR_DIGITS = 4

    private val CONTAINER_EXTENSIONS = setOf(
        "cbz", "cbr", "cb7", "cbt", "cba", "zip", "rar", "7z", "tar", "pdf", "epub",
    )

    /** Anything a scanner bracketed: "(2024)", "(Webrip)", "(The Last Kryptonian-DCP)", "[digital]". */
    private val TAG = Regex("""[(\[{][^)\]}]*[)\]}]""")

    /**
     * A bracket group the filename was truncated inside. Dropped rather than left in the series.
     * The opening bracket has to start a word, so a stray "(" mid-name ("Bat(man 001") loses only
     * itself instead of swallowing the issue number behind it.
     */
    private val DANGLING_TAG = Regex("""(?:^|(?<=\s))[(\[{][^)\]}]*$""")

    /** "2024", "2011-2015", "2011-present" — a publication year or the start of a run. */
    private val YEAR_TAG = Regex("""^(\d{4})(?:\s*-\s*(?:\d{4}|\?{4}|present))?$""", RegexOption.IGNORE_CASE)

    /** Longest alternative first so "volume" is never matched as "vol" with "ume" left over. */
    private val VOLUME = Regex("""(?iu)(?<![\p{L}\d])(?:volume|vol|v)\.?\s*(\d{1,4})(?![\p{L}\d])""")

    /**
     * An explicitly prefixed issue number. The word prefixes need a left word boundary or "c" would
     * fire inside "Classic"; "#" does not, because "Batman#5" is a real spelling.
     */
    private val PREFIXED_ISSUE = Regex(
        """(?iu)(?:#|(?<![\p{L}\d])(?:chapter|chap|ch|c|issue|part|pt|tome|t))""" +
            """\.?\s*(\d{1,5}(?:\.\d{1,3})?)(?![\p{L}\d])"""
    )

    /**
     * A number the filename opens with, as in "Series/001.cbz" or "Series/001 - Title.cbz".
     *
     * It only counts when a separator or the end of the name follows it. Without that guard
     * "2000 AD 1234.cbz" would parse as issue 2000 of an unnamed series instead of issue 1234 of
     * "2000 AD".
     */
    private val LEADING_ISSUE = Regex("""^(\d{1,5}(?:\.\d{1,3})?)\s*(?:[-–—.:]|$)""")

    /**
     * A zero-padded leading number, as in "001 To You, 2000 Years From Now.cbz". The padding is
     * what distinguishes it from a series that merely opens with a number: "2000 AD 1234.cbz" is
     * issue 1234 of "2000 AD", not issue 2000. §5.1 expects the series to come from the folder.
     */
    private val PADDED_LEADING_ISSUE = Regex("""^(0\d{1,4}(?:\.\d{1,3})?)(?![\d])""")

    /** "001-012", "1-12" — a collected run. The first number identifies it. */
    private val ISSUE_RANGE = Regex("""(?u)(?<![\p{L}\d])(\d{1,5})\s*[-–—]\s*(\d{1,5})(?![\p{L}\d])""")

    /** Any standalone number, used as the issue of last resort. */
    private val STANDALONE_NUMBER = Regex("""(?u)(?<![\p{L}\d])(\d{1,5}(?:\.\d{1,3})?)(?![\p{L}\d])""")

    private val WHITESPACE_RUN = Regex("""\s+""")

    /**
     * A dot a scene release used where everyone else uses a space. A dot with a digit on both sides
     * is excluded, because that is a fractional chapter: "10.5.cbz" is issue 10.5, not issue 5.
     */
    private val DOT_SEPARATOR = Regex("""(?<!\d)\.|\.(?!\d)""")

    /** Separator debris left at a fragment's edges once the tokens around it have been removed. */
    private const val EDGE_JUNK = " \t -–—_.,:;~|([{)]}#"

    /**
     * @param path a container path; may be bare ("001.cbz") or nested, with either separator.
     * @param policy [TitlePolicy.ORIGINAL_FILENAME] skips parsing entirely — see [TitlePolicy].
     */
    fun parse(path: String, policy: TitlePolicy = TitlePolicy.PARSED): ParsedName {
        val segments = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        // A path with no separators still yields one segment, so an empty list means the path was
        // empty or nothing but separators. There is no filename in that case, and falling back to
        // the path itself would hand "///" back as a series.
        val filename = segments.lastOrNull().orEmpty()

        if (policy == TitlePolicy.ORIGINAL_FILENAME) {
            // The escape hatch means "do not parse", not "parse and then ignore it": leaving the
            // heuristic fields populated would let a wrong guess leak back in through sorting.
            return ParsedName(
                title = stripExtension(filename).ifBlank { filename },
                originalFilename = filename,
            )
        }

        val core = parseCore(stripExtension(filename))
        if (!core.series.isNullOrBlank()) {
            return core.toParsedName(filename)
        }

        // The filename carried no series of its own — which is exactly what "001.cbz" or
        // "001 - Title.cbz" looks like once the number is taken out. Walk up for one, picking up a
        // "v01" folder's volume on the way so "Series/v01/001.cbz" keeps both.
        var volume = core.volume
        var year = core.year
        for (i in segments.size - 2 downTo 0) {
            val ancestor = parseCore(segments[i])
            volume = volume ?: ancestor.volume
            year = year ?: ancestor.year
            val ancestorSeries = folderSeries(segments[i])
            if (!ancestorSeries.isNullOrBlank()) {
                return core.copy(
                    series = ancestorSeries,
                    volume = volume,
                    year = year,
                    // "001 Chapter Name.cbz" leaves "Chapter Name" over; that is the title, not a
                    // second copy of the series.
                    title = core.title?.takeIf { it != ancestorSeries },
                ).toParsedName(filename, seriesFromFolder = true)
            }
        }
        return core.copy(volume = volume, year = year).toParsedName(filename)
    }

    private data class Core(
        val series: String? = null,
        val issue: IssueNumber? = null,
        val volume: Int? = null,
        val year: Int? = null,
        val title: String? = null,
    ) {
        fun toParsedName(filename: String, seriesFromFolder: Boolean = false) = ParsedName(
            series = series,
            issue = issue,
            volume = volume,
            year = year,
            title = title,
            originalFilename = filename,
            seriesFromFolder = seriesFromFolder,
        )
    }

    /**
     * A folder name IS the series. Unlike a filename it carries no issue number, so no number is
     * taken out of it — parsing it as a filename turned "Spider-Man 2099" into "Spider-Man" and
     * "Kaiju No. 8" into "Kaiju No", quietly merging distinct shelves.
     *
     * A "v01" folder still yields nothing, so the walk continues to the real series above it.
     */
    private fun folderSeries(text: String): String? {
        val detagged = DANGLING_TAG.replace(TAG.replace(text, " "), " ")
        val normalised = normaliseSeparators(detagged)
        val volumeMatch = VOLUME.find(normalised)
        return clean(volumeMatch?.let { blank(normalised, it.range) } ?: normalised)
    }


    /** Which number is the issue, which is a year, and the text with the year taken out. */
    private data class Numbers(
        val issue: MatchResult?,
        val yearToken: MatchResult?,
        val masked: String,
        val year: Int?,
    )

    /**
     * Decides the issue and the year together, because they compete for the same digits.
     *
     * Order matters. An explicit marker outranks everything: "#1900" is an issue even though 1900
     * reads like a year, and looking for the year first blanked those digits, so "Series 100
     * #1900" fell back to 100 and shelved the book under "Series".
     */
    private fun resolveNumbers(text: String, hasVolume: Boolean, taggedYear: Int?): Numbers {
        val explicit = PADDED_LEADING_ISSUE.find(text)
            ?: LEADING_ISSUE.find(text)
            ?: PREFIXED_ISSUE.findAll(text).lastOrNull()
            ?: ISSUE_RANGE.findAll(text).lastOrNull()

        // A year is never the explicit issue's digits, and never the FIRST number, because a
        // series may open with one ("2000 AD 1234").
        val standalone = STANDALONE_NUMBER.findAll(text)
            .filterNot { explicit != null && it.range overlaps explicit.range }
            .toList()
        val yearToken = standalone.drop(1).lastOrNull { looksLikeYear(it.groupValues[1]) }
        val masked = yearToken?.let { blank(text, it.range) } ?: text

        // With a volume marker present the book is identified by its volume, so a bare number
        // left over belongs to the series: "Kaiju No. 8 v01" is volume 1, not issue 8.
        val issue = explicit
            ?: masked.takeIf { !hasVolume }?.let { STANDALONE_NUMBER.findAll(it).lastOrNull() }

        return Numbers(
            issue = issue,
            yearToken = yearToken,
            masked = masked,
            year = taggedYear ?: yearToken?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    private infix fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    private fun looksLikeYear(digits: String): Boolean =
        digits.length == YEAR_DIGITS && '.' !in digits &&
            (digits.toIntOrNull() ?: 0) in PLAUSIBLE_YEARS

    private fun parseCore(text: String): Core {
        val year = TAG.findAll(text)
            .mapNotNull { YEAR_TAG.find(it.value.trim('(', ')', '[', ']', '{', '}').trim())?.groupValues?.get(1) }
            .mapNotNull { it.toIntOrNull() }
            .firstOrNull { it in 1900..2199 }

        val detagged = TAG.replace(text, " ").let { DANGLING_TAG.replace(it, " ") }
        val normalised = normaliseSeparators(detagged)

        val volumeMatch = VOLUME.findAll(normalised).lastOrNull()
        // Blank the volume token before hunting for the issue, so "Batman v02" cannot report
        // volume 2 and issue 2 off the same digits.
        val volumeMasked = volumeMatch?.let { blank(normalised, it.range) } ?: normalised

        val numbers = resolveNumbers(volumeMasked, volumeMatch != null, year)
        val issueMatch = numbers.issue
        val masked = numbers.masked
        val resolvedYear = numbers.year
        val yearToken = numbers.yearToken

        val spans = listOfNotNull(volumeMatch?.range, issueMatch?.range, yearToken?.range)
        val series: String?
        val title: String?
        if (spans.isEmpty()) {
            series = clean(masked)
            title = null
        } else {
            series = clean(masked.substring(0, spans.minOf { it.first }))
            title = clean(masked.substring(spans.maxOf { it.last } + 1))
        }

        return Core(
            series = series,
            issue = issueMatch?.let { IssueNumber.parse(it.groupValues[1]) },
            volume = volumeMatch?.groupValues?.get(1)?.toIntOrNull(),
            year = resolvedYear,
            title = title,
        )
    }

    private fun blank(text: String, range: IntRange): String = buildString(text.length) {
        append(text, 0, range.first)
        repeat(range.last - range.first + 1) { append(' ') }
        append(text, range.last + 1, text.length)
    }

    private fun normaliseSeparators(text: String): String {
        val underscored = text.replace('_', ' ')
        // Only rewrite dots when the name has no spaces at all — a name that already has spaces is
        // using them as its separator, so any dot left in it is part of a number or an initialism.
        val dotted = if (' ' in underscored) underscored else DOT_SEPARATOR.replace(underscored, " ")
        return WHITESPACE_RUN.replace(dotted, " ").trim()
    }

    private fun clean(fragment: String): String? =
        WHITESPACE_RUN.replace(fragment, " ").trim { it in EDGE_JUNK }.takeIf { it.isNotEmpty() }

    /**
     * Drops a trailing extension. Known container extensions go unconditionally; anything else has
     * to look like an extension, which keeps "Batman Vol. 1" (trailing ".1") and "S.H.I.E.L.D."
     * from losing their tails.
     */
    private fun stripExtension(filename: String): String {
        val dot = filename.lastIndexOf('.')
        if (dot <= 0) return filename
        val extension = filename.substring(dot + 1)
        if (extension.lowercase() in CONTAINER_EXTENSIONS) return filename.substring(0, dot)
        val plausible = extension.length in 1..4 &&
            extension[0].isLetter() &&
            extension.all { it.isLetterOrDigit() }
        return if (plausible) filename.substring(0, dot) else filename
    }
}
