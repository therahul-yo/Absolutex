package com.absolutex.core.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A book the scanner found, as persisted (§3: a library scan "survives process death").
 *
 * Keyed by path because that is what the scanner can re-derive on every run without opening
 * anything. [contentKey] is the identity used for deduplication across locations — filename and
 * size — and is indexed because that is the lookup the library does per book, not per query.
 */
@Entity(
    tableName = "library_book",
    indices = [Index("series"), Index("contentKey")],
)
data class LibraryBook(
    @PrimaryKey val path: String,
    val contentKey: String,
    val series: String?,
    val title: String?,
    val issue: Double?,
    val issueRaw: String?,
    val volume: Int?,
    val year: Int?,
    val sizeBytes: Long,
    val lastModified: Long,
    val isImageFolder: Boolean,
    val pageCount: Int?,
    /** When this row was first seen, so "recently added" needs no filesystem call. */
    val addedAt: Long,
    /** Bumped by each scan; rows from an older scan are gone from disk. See [deleteStaleIn]. */
    val seenAtScan: Long,
    /** §5.1 favourites shelf: user-toggled flag that survives scans. */
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    /**
     * The container's type as a lowercase extension ("cbr", "pdf", "epub"), or "folder" for an
     * image folder. Taken from the file name at scan time: a SAF path is a document id with no
     * extension, so this is the only place the library can learn what kind of book a row is.
     * Empty on rows written before the column existed, until their next scan.
     */
    @ColumnInfo(defaultValue = "")
    val format: String = "",
    /**
     * The file's own name as its provider reports it ("Absolute Batman 001 (2024).cbz"). A SAF
     * path ends in a numeric document id, so this is the only record of the real name: what a
     * document shows as its title, and what "use original filename" means. Empty on rows written
     * before the column existed, until their next scan.
     */
    @ColumnInfo(defaultValue = "")
    val fileName: String = "",
)

/** [LibraryBook.format] for an image folder, which has no extension of its own. */
const val FOLDER_FORMAT = "folder"

/** Just the columns the upsert has to preserve across a rescan. */
data class BookOrigin(val path: String, val addedAt: Long, val isFavorite: Boolean, val format: String)

@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(books: List<LibraryBook>)

    @Query("SELECT path, addedAt, isFavorite, format FROM library_book WHERE path IN (:paths)")
    suspend fun originsOf(paths: List<String>): List<BookOrigin>

    /**
     * Upsert that keeps [LibraryBook.addedAt] from the row already on disk.
     *
     * REPLACE deletes and reinserts, so a plain upsert resets addedAt on every scan and
     * "recently added" would show the whole library after any rescan. [LibraryBook.isFavorite]
     * is the same shape of problem: the user set it, so no scan may clear it. Everything else
     * about a book is re-derived from disk and should be overwritten.
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
                    // A scan names an .epub "epub"; that it is a text book was learned by opening
                    // it, and rescanning must not forget that.
                    format = if (kept.format == TEXT_EPUB_FORMAT && book.format == "epub") kept.format else book.format,
                )
            },
        )
    }

    @Query("SELECT * FROM library_book ORDER BY series IS NULL, series, issue")
    suspend fun allOnce(): List<LibraryBook>

    @Query("SELECT * FROM library_book ORDER BY series IS NULL, series, issue")
    fun observeAll(): Flow<List<LibraryBook>>

    @Query("SELECT * FROM library_book WHERE series = :series ORDER BY issue")
    suspend fun booksInSeries(series: String): List<LibraryBook>

    /**
     * Instant-as-you-type search (§5.1). LIKE with a leading wildcard cannot use an index, but at
     * 5,000 rows SQLite scans in well under a frame, and the alternative — FTS — is a second copy
     * of every title to keep in sync.
     *
     * [path] is a percent-encoded SAF document Uri for a SAF book, so a typed query never matches
     * one on [path] alone (a space never appears as a literal space there). [encoded] is [query]
     * percent-encoded the same way, so the row still matches on the encoded path even when the
     * decoded filename was never persisted as its own column.
     */
    @Query(
        """
        SELECT * FROM library_book
        WHERE series LIKE '%' || :query || '%'
           OR title  LIKE '%' || :query || '%'
           OR path   LIKE '%' || :query || '%'
           OR path   LIKE '%' || :encoded || '%'
        ORDER BY series IS NULL, series, issue
        """,
    )
    suspend fun search(query: String, encoded: String): List<LibraryBook>

    /**
     * Removes every row for [path] — for a path the watcher reported as gone (§5.1 file
     * monitoring). Scoped to the single row: a one-file delete must never reach another path.
     */
    @Query("DELETE FROM library_book WHERE path = :path")
    suspend fun deletePath(path: String): Int

    /**
     * Removes rows the latest scan did not see — the files are gone from disk (§5.1 file
     * monitoring). Scoped to [pathPrefix] so scanning one location never deletes another's books.
     */
    @Query("DELETE FROM library_book WHERE seenAtScan < :scanId AND path LIKE :pathPrefix || '%'")
    suspend fun deleteStaleIn(pathPrefix: String, scanId: Long): Int

    @Query("UPDATE library_book SET isFavorite = :favorite WHERE path = :path")
    suspend fun updateFavorite(path: String, favorite: Boolean): Int

    @Query("DELETE FROM library_book")
    suspend fun clear()
}
