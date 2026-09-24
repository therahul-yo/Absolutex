package com.absolutex.core.data

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Query

/**
 * Facts about a library book learned after its scan, by whatever opened it: what kind of book it
 * really is, how many pages it has. Single-column updates on `library_book`, kept apart from
 * [LibraryDao] so that interface stays about scanning and listing.
 */
@Dao
interface BookFactsDao {
    /**
     * Records that the book at [path] is a [format] after all — a scan names a book by extension,
     * and an `.epub` is a comic or a text book only once its package has been read.
     */
    @Query("UPDATE library_book SET format = :format WHERE path = :path AND format != :format")
    suspend fun updateFormat(path: String, format: String): Int

    /**
     * Whether the book with this identity is a favourite, or null when the library does not hold
     * it (a book opened from a file manager). By identity, not path: it is what the reader knows,
     * and the same book found in two locations is one favourite.
     */
    @Query("SELECT MAX(isFavorite) FROM library_book WHERE contentKey = :contentKey")
    fun observeFavourite(contentKey: String): Flow<Boolean?>

    @Query("UPDATE library_book SET isFavorite = :favourite WHERE contentKey = :contentKey")
    suspend fun setFavourite(contentKey: String, favourite: Boolean): Int

    /**
     * Persists the page count for the book at [path], but only if the stored value is still null —
     * the count from the first opener wins, and a later re-scan (which re-rows the book) must not
     * overwrite it with the same value. A scan cannot learn a page count without opening, so this
     * column stays null until the cover path (or the reader) opens the book.
     */
    @Query("UPDATE library_book SET pageCount = :count WHERE path = :path AND pageCount IS NULL")
    suspend fun updatePageCount(path: String, count: Int): Int
}

/** [LibraryBook.format] for a reflowable, text EPUB — a document rather than a comic. */
const val TEXT_EPUB_FORMAT = "epub-text"
