package com.absolutex.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
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
)

@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(books: List<LibraryBook>)

    @Query("SELECT * FROM library_book ORDER BY series IS NULL, series, issue")
    fun observeAll(): Flow<List<LibraryBook>>

    @Query("SELECT * FROM library_book WHERE series = :series ORDER BY issue")
    suspend fun booksInSeries(series: String): List<LibraryBook>

    @Query("SELECT COUNT(*) FROM library_book")
    suspend fun count(): Int

    /**
     * Instant-as-you-type search (§5.1). LIKE with a leading wildcard cannot use an index, but at
     * 5,000 rows SQLite scans in well under a frame, and the alternative — FTS — is a second copy
     * of every title to keep in sync.
     */
    @Query(
        """
        SELECT * FROM library_book
        WHERE series LIKE '%' || :query || '%'
           OR title  LIKE '%' || :query || '%'
           OR path   LIKE '%' || :query || '%'
        ORDER BY series IS NULL, series, issue
        """,
    )
    suspend fun search(query: String): List<LibraryBook>

    /**
     * Removes rows the latest scan did not see — the files are gone from disk (§5.1 file
     * monitoring). Scoped to [pathPrefix] so scanning one location never deletes another's books.
     */
    @Query("DELETE FROM library_book WHERE seenAtScan < :scanId AND path LIKE :pathPrefix || '%'")
    suspend fun deleteStaleIn(pathPrefix: String, scanId: Long): Int

    @Query("DELETE FROM library_book")
    suspend fun clear()
}
