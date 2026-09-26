package com.absolutex.source.epub

/**
 * Full-text search inside one chapter of a text EPUB, and the pattern the reader highlights with.
 *
 * The reader finds a hit again in the laid-out chapter by walking its DOM text nodes (see the
 * text reader's mark script), counting matches of the same [SearchPattern.source]. For the Nth
 * match here to be the Nth match there, both sides must see the same text: so [textOf] keeps
 * only the body, drops scripts, styles and comments exactly as a text-node walk never sees them,
 * and turns every tag into [SEPARATOR], which no pattern can match across, just as a DOM match
 * never spans two text nodes.
 */
object EpubSearch {

    /** Stands in for a tag. Never whitespace and never in a query, so no match crosses it. */
    const val SEPARATOR = '\u0000'

    /** Characters either side of a match in its snippet. */
    const val SNIPPET_CONTEXT = 40

    /** Shorter queries match nearly everything in a 2,700-chapter book, and help no one. */
    const val MIN_QUERY_LENGTH = 2

    /**
     * The pattern for [query], or null when it is too short to search for. Words match in order,
     * any run of whitespace between them, ignoring case. The source is written to mean the same
     * in Java and JavaScript (`u` flag) regex, so the reader can hand it to the page verbatim.
     */
    fun pattern(query: String): SearchPattern? {
        val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.joinToString(" ").length < MIN_QUERY_LENGTH) return null
        val source = words.joinToString(SPACE_CLASS) { escape(it) }
        // Unicode case folding, as JavaScript's `iu` flags do; the inline flag stays Kotlin-side.
        return SearchPattern(source, Regex("(?iu)$source"))
    }

    /** The body's text, tags as [SEPARATOR], entities decoded. Pure string work: no DOM, no limits. */
    fun textOf(document: ByteArray): String {
        var html = String(document, Charsets.UTF_8)
        BODY_OPEN.find(html)?.let { html = html.substring(it.range.last + 1) }
        html = COMMENT.replace(html, "")
        html = SCRIPT_OR_STYLE.replace(html, SEPARATOR.toString())
        html = TAG.replace(html, SEPARATOR.toString())
        return decodeEntities(html)
    }

    /** Up to [limit] matches in [text], each with its occurrence number and a snippet around it. */
    fun matches(text: String, pattern: SearchPattern, limit: Int): List<SearchMatch> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<SearchMatch>()
        for ((occurrence, match) in pattern.regex.findAll(text).withIndex()) {
            out += snippetOf(text, occurrence, match.range)
            if (out.size >= limit) break
        }
        return out
    }

    private fun snippetOf(text: String, occurrence: Int, range: IntRange): SearchMatch {
        val start = (range.first - SNIPPET_CONTEXT).coerceAtLeast(0)
        val end = (range.last + 1 + SNIPPET_CONTEXT).coerceAtMost(text.length)
        val before = tidy(text.substring(start, range.first)).trimStart()
        val hit = tidy(text.substring(range.first, range.last + 1))
        val after = tidy(text.substring(range.last + 1, end)).trimEnd()
        val lead = if (start > 0) "…" else ""
        val tail = if (end < text.length) "…" else ""
        val snippet = lead + before + hit + after + tail
        val at = lead.length + before.length
        return SearchMatch(occurrence, snippet, at, at + hit.length)
    }

    /** Separators out, whitespace runs to one space: a snippet reads as prose, not markup. */
    private fun tidy(part: String): String =
        part.replace(SEPARATOR.toString(), "").replace(WHITESPACE, " ")

    private fun decodeEntities(html: String): String = ENTITY.replace(html) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x", ignoreCase = true) -> codePoint(name.drop(2).toIntOrNull(HEX))
            name.startsWith("#") -> codePoint(name.drop(1).toIntOrNull())
            else -> NAMED[name.lowercase()]
        } ?: match.value
    }

    private fun codePoint(value: Int?): String? =
        value?.takeIf { Character.isValidCodePoint(it) && it != 0 }?.let { String(Character.toChars(it)) }

    /** Escapes the characters that are syntax in both regex dialects; nothing else, for JS's `u` flag. */
    private fun escape(word: String): String = buildString {
        for (c in word) {
            if (c in REGEX_SYNTAX) append('\\')
            append(c)
        }
    }

    private const val HEX = 16
    private const val REGEX_SYNTAX = "\\^$.|?*+()[]{}/"

    /** Whitespace as both dialects agree on it, including the no-break space books are full of. */
    private const val SPACE_CLASS = "[ \\t\\n\\r\\f\\u00A0]+"
    private val WHITESPACE = Regex("[ \\t\\n\\r\\f\\u00A0]+")
    private val BODY_OPEN = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val SCRIPT_OR_STYLE =
        Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”",
    )
}

/** A query's regex, and its [source] for the page script to rebuild the same pattern from. */
class SearchPattern internal constructor(val source: String, internal val regex: Regex)

/**
 * One hit: the [occurrence]-th match in its chapter (from 0), and a [snippet] of the prose around
 * it with the match at [matchStart] until [matchEnd].
 */
data class SearchMatch(val occurrence: Int, val snippet: String, val matchStart: Int, val matchEnd: Int)
