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

/**
 * The home tabs, in the order they matter: comics first (this is a comic reader), then what was
 * read lately and favourites, and documents — PDFs and text EPUBs — last (§5.1).
 */
internal enum class HomeSection { COMICS, RECENT, FAVORITES, DOCUMENTS }

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
        private const val DEFAULT_PORTRAIT = 2
        private const val DEFAULT_LANDSCAPE = 4

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
    /** Lowercase container extension ("cbr", "pdf"), "folder", or empty when not yet known. */
    val format: String = "",
    /** When the reader last saved a position in this book; null if never opened. */
    val lastReadAt: Long? = null,
) {

    /**
     * The date to show and sort by. A SAF document has no `File.lastModified` (it reads as 0), so
     * those books fall back to when the library first saw them.
     */
    val date: Long
        get() = lastModified.takeIf { it > 0 } ?: addedAt

    /** A PDF is a book; a reflowable EPUB ("epub-text") is too — only fixed-layout comic EPUBs stay comics. */
    val isBook: Boolean
        get() = format == "pdf" || format == "epub-text"


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

