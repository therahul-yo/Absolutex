package com.absolutex.core.data

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
}

/** [LibraryBook.format] for a reflowable, text EPUB — a document rather than a comic. */
const val TEXT_EPUB_FORMAT = "epub-text"
