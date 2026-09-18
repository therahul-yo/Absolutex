package com.absolutex.feature.widget

import com.absolutex.core.data.LibraryDao
import com.absolutex.core.data.ProgressDao

/** Room-backed source; progress is read only through existing ProgressDao methods, no new queries. */
class RoomContinueReadingSource(
    private val progress: ProgressDao,
    private val library: LibraryDao,
) : ContinueReadingSource {
    override suspend fun inProgress(limit: Int): List<WidgetModel> {
        val count = limit.coerceAtLeast(0).coerceAtMost(WIDGET_MAX_ITEMS)
        if (count == 0) return emptyList()
        // mostRecent is the only recency read ProgressDao offers, so today this is one row;
        // the cap binds later multi-row sources.
        val row = progress.mostRecent() ?: return emptyList()
        val match = library.allOnce().firstOrNull { it.contentKey == row.bookId }
        val title = match?.let { displayTitle(it.title, it.series, it.path) } ?: row.bookId
        // coverKey stays null: :core:thumbnails is unmerged, and invented art is worse than none.
        return listOf(WidgetModel.from(row.bookId, title, row.pageIndex, row.pageCount, null)).take(count)
    }

    /** File path behind the tap deep link; null when the book left the library (falls back to the raw id). */
    suspend fun libraryPathFor(bookId: String): String? =
        library.allOnce().firstOrNull { it.contentKey == bookId }?.path
}
