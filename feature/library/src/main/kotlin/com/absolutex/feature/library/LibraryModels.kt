package com.absolutex.feature.library

import java.io.File

/**
 * How far through a book the reader got.
 *
 * Derived from the page position rather than stored: a "read" flag and a reading position are two
 * facts that can disagree, and the position is the one the reader actually writes.
 */
internal enum class ReadState { UNREAD, IN_PROGRESS, FINISHED }

/** The three browse presentations §5.1 asks for. */
internal enum class BrowseLayout { SIMPLE_LIST, DETAILED_LIST, GRID }

/** Home shelves (§5.1). */
internal enum class HomeSection { READING, SERIES, FOLDERS, UNREAD, FAVORITES }

/** Which sort the browse views are under. [com.absolutex.core.scan.SortKey] names the field. */
internal data class SortSpec(val key: com.absolutex.core.scan.SortKey, val ascending: Boolean)

/**
 * Grid density, configured per orientation (§5.1).
 *
 * Two independent numbers rather than one scaled by aspect ratio: covers are portrait, so the
 * count that looks right in landscape is not a function of the portrait count — the user picks
 * both and we remember both.
 */
internal data class GridSpec(val portraitColumns: Int, val landscapeColumns: Int) {

    fun columnsFor(landscape: Boolean): Int =
        (if (landscape) landscapeColumns else portraitColumns).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun withColumns(landscape: Boolean, columns: Int): GridSpec {
        val clamped = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        return if (landscape) copy(landscapeColumns = clamped) else copy(portraitColumns = clamped)
    }

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 8
        private const val DEFAULT_PORTRAIT = 3
        private const val DEFAULT_LANDSCAPE = 5

        val Default = GridSpec(DEFAULT_PORTRAIT, DEFAULT_LANDSCAPE)
    }
}

/**
 * One library row, as the screens need it.
 *
 * Deliberately not [com.absolutex.core.data.LibraryBook]: that is a Room entity carrying scan
 * bookkeeping (contentKey, seenAtScan) the UI has no business knowing, and keeping the UI model
 * separate is what lets the whole state layer below be plain Kotlin — compiled and tested with no
 * Android toolchain.
 *
 * Every field is required. A nullable field with a default is how `TileKey.bookId` silently
 * defaulted to "" at every call site and made a book-scoped cache scope nothing; the cost of
 * typing `isFavorite = false` at three call sites is much lower than that.
 */
internal data class LibraryBookUi(
    /** Absolute path. The stable identity for selection and for talking to the repository. */
    val path: String,
    val displayName: String,
    val originalFilename: String,
    val series: String?,
    val sizeBytes: Long,
    /** Persisted at scan time, so sorting by date costs no filesystem call. */
    val lastModified: Long,
    val addedAt: Long,
    val pageCount: Int?,
    /** 0-based position the reader last settled on; null when the book was never opened. */
    val currentPage: Int?,
    val isFavorite: Boolean,
) {

    val readState: ReadState
        get() = when {
            currentPage == null || currentPage <= 0 -> ReadState.UNREAD
            pageCount != null && currentPage >= pageCount - 1 -> ReadState.FINISHED
            else -> ReadState.IN_PROGRESS
        }

    /** 0f..1f for a progress indicator, or null when there is nothing meaningful to draw. */
    val progressFraction: Float?
        get() {
            val page = currentPage ?: return null
            val count = pageCount ?: return null
            if (count <= 1) return null
            return ((page + 1).toFloat() / count).coerceIn(0f, 1f)
        }

    /** Parent directory, the grouping key for the Folders shelf. */
    val folderPath: String
        get() = File(path).parent.orEmpty()

    val folderName: String
        get() = File(path).parentFile?.name.orEmpty().ifEmpty { folderPath }
}

/** What the batch action bar can ask for over a selection (§5.1). */
internal enum class BatchAction { MARK_READ, MARK_UNREAD, FAVORITE, UNFAVORITE, DELETE }

/** A named group of books — a series shelf or a folder shelf. */
internal data class Shelf(val name: String, val books: List<LibraryBookUi>) {
    val size: Int get() = books.size
    val totalBytes: Long get() = books.sumOf { it.sizeBytes }
}

/**
 * Human-readable byte count for the detailed list (§5.1).
 *
 * Pure and `Locale.ROOT`, so it is testable off-device and identical everywhere. The localised
 * alternative is `android.text.format.Formatter.formatFileSize`, which needs a Context and cannot
 * be unit-tested here.
 * TODO(library): switch to the platform formatter when the app grows a second locale.
 */
internal fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < UNIT) return "$bytes B"
    var value = bytes.toDouble()
    // Counts divisions, so it is 1 once we are in kilobytes — hence the -1 when naming the unit.
    var divisions = 0
    while (value >= UNIT && divisions < UNIT_NAMES.size) {
        value /= UNIT
        divisions++
    }
    // One decimal below 10 (9.4 MB), none above: a tenth of a megabyte is noise at 412 MB.
    val rounded = if (value < DECIMAL_BELOW) {
        String.format(java.util.Locale.ROOT, "%.1f", value)
    } else {
        value.toLong().toString()
    }
    return "$rounded ${UNIT_NAMES[divisions - 1]}"
}

private const val UNIT = 1024.0
private const val DECIMAL_BELOW = 10.0
private val UNIT_NAMES = listOf("KB", "MB", "GB", "TB")
