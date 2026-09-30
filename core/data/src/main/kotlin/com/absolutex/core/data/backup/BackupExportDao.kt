package com.absolutex.core.data.backup

import androidx.room.Dao
import androidx.room.Query
import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.PageView
import com.absolutex.core.data.OPENING_PAGE_MARKER
import com.absolutex.core.data.ReadingProgress

data class ExportFavourite(val identity: String, val time: Long)

data class ExportCounts(
    val progress: Long, val history: Long, val bookmarks: Long, val prefs: Long, val favourites: Long,
)

/** Bounded reads even when the on-device tables are far larger than a backup. */
@Dao
interface BackupExportDao {
    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC, bookId LIMIT :limit")
    suspend fun progress(limit: Int): List<ReadingProgress>

    @Query("SELECT * FROM page_view WHERE page != $OPENING_PAGE_MARKER ORDER BY atEpochMs DESC, id DESC LIMIT :limit")
    suspend fun history(limit: Int): List<PageView>

    @Query("SELECT * FROM bookmark ORDER BY createdAt DESC, bookId, pageIndex LIMIT :limit")
    suspend fun bookmarks(limit: Int): List<Bookmark>

    @Query("""SELECT * FROM book_prefs ORDER BY MAX(
        COALESCE((SELECT MAX(updatedAt) FROM reading_progress WHERE bookId = book_prefs.bookId), 0),
        COALESCE((SELECT MAX(atEpochMs) FROM page_view
            WHERE page != $OPENING_PAGE_MARKER AND bookKey = book_prefs.bookId), 0)
        ) DESC, bookId LIMIT :limit""")
    suspend fun bookPrefs(limit: Int): List<BookPrefs>

    @Query("""SELECT contentKey AS identity, MAX(
        COALESCE((SELECT MAX(updatedAt) FROM reading_progress WHERE bookId = contentKey), 0),
        COALESCE((SELECT MAX(atEpochMs) FROM page_view
            WHERE page != $OPENING_PAGE_MARKER AND bookKey = contentKey), 0), MAX(addedAt)
         ) AS time FROM library_book WHERE isFavorite = 1 GROUP BY contentKey
        ORDER BY time DESC, identity LIMIT :limit""")
    suspend fun favourites(limit: Int): List<ExportFavourite>

    @Query("""SELECT (SELECT COUNT(*) FROM reading_progress) AS progress,
        (SELECT COUNT(*) FROM page_view WHERE page != $OPENING_PAGE_MARKER) AS history,
        (SELECT COUNT(*) FROM bookmark) AS bookmarks,
        (SELECT COUNT(*) FROM book_prefs) AS prefs,
        (SELECT COUNT(DISTINCT contentKey) FROM library_book WHERE isFavorite = 1) AS favourites""")
    suspend fun counts(): ExportCounts
}
