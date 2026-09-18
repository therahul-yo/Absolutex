package com.absolutex.core.data

import com.absolutex.source.NaturalOrder

/**
 * Where auto-advance (§5.2) is allowed to look for the next book: the whole library, matched by
 * parsed series, or just the folder the current book sits in — some libraries have no series tags
 * at all, and folder order is the only order that shelf ever had.
 */
enum class NextBookScope { WHOLE_LIBRARY, CURRENT_FOLDER }

/**
 * How the books [NextBookScope] finds are ordered, to decide which one is "next" (§5.2).
 *
 * PARSED_NUMBER follows the scanner's own issue number, the same one the library sorts by;
 * RAW_FILENAME ignores it entirely, for a series the parser gets wrong where the filenames
 * themselves are already in the right order.
 */
enum class NextBookOrder { PARSED_NUMBER, RAW_FILENAME }

/**
 * The book that comes straight after [current] (§5.2 auto-advance).
 *
 * Pure and total: no I/O, no Android. [candidates] is whatever pool the caller has on hand — the
 * whole library is fine, since [scope] narrows it here — and every case that isn't "yes, this one"
 * resolves to null rather than throwing: a book with no series under [NextBookScope.WHOLE_LIBRARY],
 * a current book the pool does not contain, a last book with nothing after it.
 */
object NextBook {

    fun after(
        current: LibraryBook,
        candidates: List<LibraryBook>,
        scope: NextBookScope,
        order: NextBookOrder,
    ): LibraryBook? {
        val ordered = candidates.filter { inScope(it, current, scope) }.sortedWith(comparatorFor(order))
        val index = ordered.indexOf(current)
        return if (index < 0) null else ordered.getOrNull(index + 1)
    }

    private fun inScope(book: LibraryBook, current: LibraryBook, scope: NextBookScope): Boolean =
        when (scope) {
            // A book with no series has nothing to auto-advance into library-wide; CURRENT_FOLDER
            // is the scope for exactly that shelf.
            NextBookScope.WHOLE_LIBRARY -> current.series != null && book.series == current.series
            NextBookScope.CURRENT_FOLDER -> BookPath.parentOf(book.path) == BookPath.parentOf(current.path)
        }

    private fun comparatorFor(order: NextBookOrder): Comparator<LibraryBook> = when (order) {
        NextBookOrder.RAW_FILENAME -> compareBy(NaturalOrder) { book -> BookPath.nameOf(book.path) }
        NextBookOrder.PARSED_NUMBER -> compareBy<LibraryBook, Double?>(nullsLast()) { it.issue }
            .thenBy(NaturalOrder) { book -> BookPath.nameOf(book.path) }
    }
}
