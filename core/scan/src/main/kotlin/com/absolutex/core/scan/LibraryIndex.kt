package com.absolutex.core.scan

import java.io.File
import java.util.Locale

/** A shelf: every book the index believes belongs to one series. */
data class Series(
    val name: String,
    val books: List<ScannedBook>,
) {
    val totalBytes: Long get() = books.sumOf { it.sizeBytes }
    val size: Int get() = books.size
}

enum class SortKey { NAME, SIZE, DATE }

/**
 * Turns a flat scan into the shelves, ordering and search the library screens need (§5.1).
 *
 * Pure functions over a list, deliberately: this is the logic most likely to be wrong, and
 * keeping it out of Room and off the UI means it can be tested exhaustively on the JVM.
 */
object LibraryIndex {

    /**
     * Drops books that are the same file reached through different locations (§5.1).
     *
     * Identity is (filename, size), not path: the point is to catch one file visible through two
     * configured locations — an SD card mounted twice, a folder that is both its own location and
     * inside another. Hashing contents would be correct and unaffordable; a name-and-size match
     * on a comic archive is not a coincidence worth worrying about.
     *
     * The first occurrence wins, so the order roots were configured in decides which path the
     * library shows.
     */
    fun deduplicate(books: List<ScannedBook>): List<ScannedBook> {
        val seen = HashSet<Pair<String, Long>>(books.size * 2)
        // displayName, not File(path).name: path is a content:// Uri for a SAF-scanned book.
        return books.filter { seen.add(it.displayName to it.sizeBytes) }
    }

    /**
     * Groups into shelves by parsed series, falling back to the parent folder name (§5.1).
     *
     * A book whose filename yielded no series is not dropped into one big unnamed shelf: its
     * folder is the best guess available, and a shelf per folder is what the user already sees
     * on disk.
     */
    fun groupIntoSeries(books: List<ScannedBook>): List<Series> =
        books.groupBy { shelfOf(it) }
            .map { (name, shelf) -> Series(name, shelf) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    private fun shelfOf(book: ScannedBook): String =
        book.parsed.series
            ?: File(book.path).parentFile?.name
            ?: book.displayName

    /**
     * @param ascending false reverses; §5.1 wants both directions for every key.
     */
    fun sort(books: List<ScannedBook>, key: SortKey, ascending: Boolean = true): List<ScannedBook> {
        val ordered = when (key) {
            // Natural order, so "issue 2" precedes "issue 10" — the whole reason NaturalOrder
            // exists. Sorting these by String.compareTo would interleave every double-digit issue.
            SortKey.NAME -> books.sortedWith(
                compareBy(com.absolutex.source.NaturalOrder) { it.displayName },
            )
            SortKey.SIZE -> books.sortedBy { it.sizeBytes }
            SortKey.DATE -> books.sortedBy { File(it.path).lastModified() }
        }
        return if (ascending) ordered else ordered.reversed()
    }

    /**
     * Case-insensitive substring match over series, title and filename (§5.1).
     *
     * §5.1 asks for instant-as-you-type on 5,000+ items, which rules out building an index per
     * keystroke. A linear scan over 5,000 in-memory strings is well under a frame, so this stays
     * a scan — the cheapest thing that meets the requirement.
     */
    fun search(books: List<ScannedBook>, query: String): List<ScannedBook> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return books
        return books.filter { book ->
            book.parsed.series?.lowercase(Locale.ROOT)?.contains(needle) == true ||
                book.parsed.title?.lowercase(Locale.ROOT)?.contains(needle) == true ||
                book.displayName.lowercase(Locale.ROOT).contains(needle)
        }
    }
}
