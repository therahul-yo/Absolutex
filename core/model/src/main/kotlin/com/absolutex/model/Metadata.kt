package com.absolutex.model

import kotlin.math.floor

/**
 * Where a book's display name comes from.
 *
 * [ORIGINAL_FILENAME] is the global escape hatch required by §5.1. Filename parsing is a
 * heuristic over other people's naming conventions, so the whole of it has to be switchable off
 * in one place rather than argued with book by book.
 */
enum class TitlePolicy { PARSED, ORIGINAL_FILENAME }

/**
 * An issue, chapter or part number.
 *
 * Both forms are kept because a single series routinely mixes "1", "001" and "10.5": [value] is
 * what we sort and compare by, [raw] is what the scanner actually wrote and what we render, so a
 * "10.5" side-story does not collapse onto the real issue 10 in the library grid.
 *
 * Note the deliberate asymmetry: equality is exact (both fields), ordering is by [value] alone.
 * Two spellings of the same number tie in a sort without being equal.
 */
data class IssueNumber(val value: Double, val raw: String) : Comparable<IssueNumber> {

    override fun compareTo(other: IssueNumber): Int = value.compareTo(other.value)

    /** Canonical numeric text: whole numbers lose the ".0", fractions keep theirs. */
    fun format(): String = if (value == floor(value)) value.toLong().toString() else value.toString()

    companion object {
        /** A leading plain decimal. Anchored at the start only, so "1A" and "5b" still parse. */
        private val LEADING_DECIMAL = Regex("""^\d{1,9}(?:\.\d{1,3})?""")

        /**
         * Parses text that *begins* with a plain decimal, keeping the whole trimmed text as [raw].
         *
         * Deliberately stricter than [String.toDoubleOrNull], which happily accepts "NaN",
         * "Infinity" and "0x1p3" — none of which is an issue number. Deliberately looser than an
         * exact match, because ComicInfo.xml really does contain `<Number>1A</Number>`.
         */
        fun parse(text: String): IssueNumber? {
            val raw = text.trim()
            val digits = LEADING_DECIMAL.find(raw)?.value ?: return null
            return IssueNumber(digits.toDouble(), raw)
        }
    }
}

/**
 * What [com.absolutex.source.FilenameParser] recovered from a path. Every field is nullable —
 * real libraries contain files that say nothing useful, and §2 says degrade rather than guess.
 */
data class ParsedName(
    val series: String? = null,
    val issue: IssueNumber? = null,
    val volume: Int? = null,
    val year: Int? = null,
    val title: String? = null,
    /** The last path segment exactly as it sat on disk, extension included. */
    val originalFilename: String = "",
    /** True when [series] came from an ancestor folder because the filename alone carried none. */
    val seriesFromFolder: Boolean = false,
) {
    /**
     * Human-readable label: "Absolute Batman #1", "Batman Vol. 3 - Year One", or the raw filename.
     *
     * Numbers alone do not identify a book, so with neither a series nor a title we show the name
     * on disk: a library row reading "#1" is indistinguishable from every other first issue, while
     * the filename is something the user recognises.
     */
    val displayName: String
        get() {
            if (series == null && title == null) return originalFilename
            return buildString {
                if (series != null) append(series)
                if (volume != null) {
                    if (isNotEmpty()) append(' ')
                    append("Vol. ").append(volume)
                }
                if (issue != null) {
                    if (isNotEmpty()) append(' ')
                    // Render the scanner's own spelling minus its padding: "001" shows as "#1",
                    // but "10.5" and "1A" keep the form that distinguishes them.
                    // Strip padding zeros ("001" -> "1") without eating the leading zero of a
                    // fraction: trimming "0.5" alone leaves ".5".
                    val shown = issue.raw.trimStart('0').ifEmpty { "0" }
                    append('#').append(if (shown.startsWith('.')) "0$shown" else shown)
                }
                if (title != null && title != series) {
                    if (isNotEmpty()) append(" - ")
                    append(title)
                }
            }.ifBlank { originalFilename }
        }
}

/**
 * ComicInfo.xml page roles. An unrecognised value becomes [OTHER] rather than failing the parse —
 * one odd page type must never cost us the writer and reading direction in the same file.
 */
enum class PageType {
    FRONT_COVER,
    INNER_COVER,
    ROUNDUP,
    STORY,
    ADVERTISEMENT,
    EDITORIAL,
    LETTERS,
    PREVIEW,
    BACK_COVER,
    DELETED,
    OTHER,
    ;

    companion object {
        fun fromComicInfo(value: String?): PageType = when (value?.trim()?.lowercase()) {
            // ComicRack omits Type for ordinary story pages, so absent and blank mean STORY.
            null, "", "story" -> STORY
            "frontcover" -> FRONT_COVER
            "innercover" -> INNER_COVER
            "roundup" -> ROUNDUP
            "advertisement" -> ADVERTISEMENT
            "editorial" -> EDITORIAL
            "letters" -> LETTERS
            "preview" -> PREVIEW
            "backcover" -> BACK_COVER
            "deleted" -> DELETED
            else -> OTHER
        }
    }
}

/** One `<Page>` element of ComicInfo.xml. */
data class ComicInfoPage(
    /** 0-based index into the container's page list, as written in the XML. */
    val image: Int,
    val type: PageType = PageType.STORY,
    /**
     * The scan holds a two-page spread inside one image. The reader needs this *before* decode so
     * it can give the spread the whole viewport instead of pairing it with a neighbour (§4).
     */
    val doublePage: Boolean = false,
    val bookmark: String? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
)

/**
 * The subset of ComicInfo.xml we act on. Absent fields stay null so a caller can tell "the file
 * did not say" from "the file said nothing useful" and fall back to the filename.
 */
data class ComicInfo(
    val series: String? = null,
    val number: IssueNumber? = null,
    val volume: Int? = null,
    val title: String? = null,
    val writers: List<String> = emptyList(),
    val year: Int? = null,
    val pageCount: Int? = null,
    val pages: List<ComicInfoPage> = emptyList(),
    /** Derived from `<Manga>`; null when the file does not say. */
    val readingFlow: ReadingFlow? = null,
) {
    /**
     * Page metadata keyed by image index. Cached because the decode pipeline hits it once per page
     * turn on the UI thread, and [pages] is a list ordered by nothing in particular.
     */
    val pagesByImage: Map<Int, ComicInfoPage> by lazy { pages.associateBy { it.image } }
}
