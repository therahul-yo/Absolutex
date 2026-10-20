package com.absolutex.core.data

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Library writes preserve durable user data while replacing rows derived from files. */
interface LibraryWrites : FavouriteQueries {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLibraryRows(books: List<LibraryBook>)

    @Query("SELECT contentKey FROM book_favourite WHERE contentKey IN (:identities)")
    suspend fun favouriteIdentities(identities: List<String>): List<String>

    /** Hydrate scanned/opened rows from durable user data, including books returning at a new path. */
    @Transaction
    suspend fun upsertAll(books: List<LibraryBook>) {
        if (books.isEmpty()) return
        books.filter { it.isFavorite }.forEach { rememberFavourite(BookFavourite(it.contentKey, it.addedAt)) }
        val favourites = favouriteIdentities(books.map { it.contentKey }).toSet()
        insertLibraryRows(books.map { it.copy(isFavorite = it.contentKey in favourites) })
    }

    @Query(
        "SELECT path, addedAt, isFavorite, format, contentKey, pageCount " +
            "FROM library_book WHERE path IN (:paths)",
    )
    suspend fun originsOf(paths: List<String>): List<BookOrigin>

    /**
     * Upsert that keeps [LibraryBook.addedAt] from the row already on disk.
     *
     * REPLACE deletes and reinserts, so a plain upsert resets addedAt on every scan and
     * "recently added" would show the whole library after any rescan. [LibraryBook.isFavorite]
     * is the same shape of problem: the user set it, so no scan may clear it. Learned page counts also survive
     * for an unchanged book identity; fresh scan counts take precedence.
     */
    @Transaction
    suspend fun upsertPreservingAddedAt(books: List<LibraryBook>) {
        if (books.isEmpty()) return
        val original = originsOf(books.map { it.path }).associateBy { it.path }
        upsertAll(
            books.map { book ->
                val kept = original[book.path] ?: return@map book
                book.copy(
                    addedAt = kept.addedAt,
                    isFavorite = kept.isFavorite,
                    pageCount = book.pageCount ?: kept.pageCount.takeIf {
                        kept.contentKey == book.contentKey
                    },
                    // A scan names an .epub "epub"; that it is a text book was learned by opening
                    // it, and rescanning must not forget that.
                    format = if (kept.format == TEXT_EPUB_FORMAT && book.format == "epub") kept.format else book.format,
                )
            },
        )
    }

    @Query("SELECT contentKey FROM library_book WHERE path = :path")
    suspend fun identityAt(path: String): String?

    @Transaction
    suspend fun updateFavorite(path: String, favorite: Boolean): Int {
        val identity = identityAt(path) ?: return 0
        return setFavourite(identity, favorite)
    }

}
