package com.absolutex.core.thumbnails

/**
 * Versioned, dependency-free journal backing [ThumbDiskCache].
 *
 * The journal is the disk cache's size accounting: an insertion-ordered map of key hex to byte
 * size, persisted as `{"version":1,"entries":[{"k":"...","s":1234}]}` with the oldest entry first.
 * It is pure Kotlin with no Android imports, so eviction accounting is covered by plain JVM tests.
 *
 * Parsing never throws: corrupt text or an unknown [JOURNAL_VERSION] yields null and the caller
 * wipes and rebuilds, so a downgrade or a half-written journal can never crash a cold start.
 * Format evolution is version-gated rather than field-tolerant: any shape change bumps the version
 * and old builds rebuild instead of guessing.
 */
class ThumbJournal private constructor(
    private val sizes: LinkedHashMap<String, Long>,
    private var total: Long,
) {

    /** Size recorded for [key], or null. Reading refreshes recency, so hot thumbs survive eviction. */
    fun sizeOf(key: String): Long? = sizes[key]

    fun put(key: String, size: Long) {
        require(size >= 0)
        val old = sizes.remove(key)
        if (old != null) total -= old
        sizes[key] = size
        total += size
    }

    fun remove(key: String): Boolean {
        val old = sizes.remove(key) ?: return false
        total -= old
        return true
    }

    /**
     * Oldest-first keys whose removal brings the total at or under [capBytes]. A single entry
     * larger than the cap evicts everything including itself, so the cap is a guarantee, not a goal.
     */
    fun keysToEvict(capBytes: Long): List<String> {
        val victims = mutableListOf<String>()
        var remaining = total
        for ((key, size) in sizes) {
            if (remaining <= capBytes) break
            victims.add(key)
            remaining -= size
        }
        return victims
    }

    fun totalBytes(): Long = total

    fun entryCount(): Int = sizes.size

    fun serialize(): String = buildString {
        append("{\"version\":")
        append(JOURNAL_VERSION)
        append(",\"entries\":[")
        var first = true
        for ((key, size) in sizes) {
            if (!first) append(',')
            first = false
            // Keys are sha256 hex by construction, so no string escaping is ever needed here.
            append("{\"k\":\"")
            append(key)
            append("\",\"s\":")
            append(size)
            append('}')
        }
        append("]}")
    }

    companion object {
        const val JOURNAL_VERSION = 1
        const val JOURNAL_FILE = "journal.json"

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        private const val EMPTY_TOTAL = 0L
        private val JOURNAL_PATTERN =
            Regex("""\{\s*"version"\s*:\s*(\d+)\s*,\s*"entries"\s*:\s*\[(.*)\]\s*\}""", RegexOption.DOT_MATCHES_ALL)
        private val ENTRY_PATTERN =
            Regex("""\{\s*"k"\s*:\s*"([0-9a-f]+)"\s*,\s*"s"\s*:\s*(\d+)\s*\}""")

        fun empty(): ThumbJournal =
            ThumbJournal(LinkedHashMap(INITIAL_CAPACITY, LOAD_FACTOR, true), EMPTY_TOTAL)

        /** Null when [json] is corrupt, foreign-shaped, or a version this build cannot read. */
        fun parse(json: String): ThumbJournal? {
            val match = JOURNAL_PATTERN.matchEntire(json.trim()) ?: return null
            return validated(match.groupValues)
        }

        private fun validated(groups: List<String>): ThumbJournal? {
            val (_, versionText, entriesText) = groups
            val versionOk = versionText.toLongOrNull() == JOURNAL_VERSION.toLong()
            val trimmed = entriesText.trim()
            return if (!versionOk) {
                null
            } else if (trimmed.isEmpty()) {
                empty()
            } else {
                parseEntries(trimmed)
            }
        }

        private fun parseEntries(raw: String): ThumbJournal? {
            val journal = empty()
            var rest = raw
            while (rest.isNotEmpty()) {
                rest = consumeEntry(journal, rest) ?: return null
            }
            return journal
        }

        private fun consumeEntry(journal: ThumbJournal, rest: String): String? {
            val trimmed = rest.trim()
            val match = ENTRY_PATTERN.find(trimmed)?.takeIf { it.range.first == 0 }
            return match?.let { consumeMatch(journal, trimmed, it) }
        }

        private fun consumeMatch(journal: ThumbJournal, trimmed: String, match: MatchResult): String? {
            val (_, key, sizeText) = match.groupValues
            val size = sizeText.toLongOrNull() ?: return null
            journal.put(key, size)
            return tailAfter(trimmed, match.range.last + 1)
        }

        private fun tailAfter(trimmed: String, end: Int): String? {
            val tail = trimmed.substring(end).trim()
            return if (tail.isEmpty()) {
                ""
            } else if (tail.startsWith(",")) {
                tail.substring(1).trim()
            } else {
                null
            }
        }
    }
}
