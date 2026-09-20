package com.absolutex.feature.library

import com.absolutex.core.data.BookPath
import com.absolutex.core.data.LibraryBook
import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.settings.AppPrefsSource
import com.absolutex.model.IssueNumber
import com.absolutex.model.ParsedName
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibraryFeed] over the shipped repository and the progress table.
 *
 * Progress joins on `LibraryBook.contentKey`, which since 6f7d70d is `BookIdentity.of(name, size)`
 * — the same key `ReadingProgress.bookId` now carries, however the book was opened.
 */
@Singleton
internal class RoomLibraryFeed @Inject constructor(
    private val repository: LibraryRepository,
    private val progressDao: ProgressDao,
    private val appPrefsSource: AppPrefsSource,
) : LibraryFeed {

    override val capabilities = LibraryCapabilities(
        // Favourites column exists (§5.1): user-toggled flag survives scans.
        canFavorite = true,
        canMarkRead = true,
        canDelete = false,
    )
        // No repository delete exists, and inventing one that removes a user's files from disk
        // is not a call this lane should make. See delete.
        canDelete = false,
    )

    override fun observeBooks(): Flow<List<LibraryBookUi>> =
        combine(repository.observeLibrary(), progressDao.observeAll(), appPrefsSource.appPrefs) {
                books, progress, prefs ->
            // Index the positions once, then look up per book: scanning the progress table per
            // row instead would be quadratic on a large library.
            val byIdentity = progress.associateBy { it.bookId }
            // Deduplicated before mapping, so the expensive part runs once per book that is shown.
            books.deduplicatedByIdentity().map { it.toUi(byIdentity, prefs.useOriginalFilename) }
            // Mapping thousands of rows is real work and Room emits on its own executor; Default
            // keeps it off both the main thread and Room's.
        }.flowOn(Dispatchers.Default)

    override suspend fun search(query: String): List<LibraryBookUi> {
        val progress = progressDao.observeAll().first().associateBy { it.bookId }
        val prefs = appPrefsSource.currentAppPrefs()
        // The DAO is the source of truth for what matches (series, title, path, and — since a SAF
        // book's path is a percent-encoded document Uri — the encoded query too). Filtering again
        // here could only ever remove rows the DAO already chose correctly, never add the ones it
        // missed, so a full parsed-label search (e.g. #1 vs stored 001) stays noted for M5 instead.
        return repository.search(query)
            .deduplicatedByIdentity()
            .map { it.toUi(progress, prefs.useOriginalFilename) }
    }

    override suspend fun setFavorite(paths: Set<String>, favorite: Boolean): LibraryNotice {
        val byPath = repository.observeLibrary().first().associateBy { it.path }
        val books = paths.mapNotNull { byPath[it] }
        if (books.isEmpty()) return LibraryNotice.BatchApplied(count = 0, skipped = paths.size)
        // Update Room rows directly: the column carries DEFAULT 0 (§5.1 favourites).
        books.forEach { book ->
            repository.upsertFavorite(book.path, favorite)
        }
        return LibraryNotice.BatchApplied(
            count = books.size,
            skipped = paths.size - books.size,
        )
    }

    /**
     * Marks a selection read, or clears its position.
     *
     * What to write is decided by [planSetRead], which knows that a container's page count is
     * unknown to the scanner and known to the reader — trusting the scan alone made Mark read on
     * an opened 45-page CBR always answer "needs opening first", and Mark unread then overwrote
     * the 45 with a 0.
     */
    override suspend fun setRead(paths: Set<String>, read: Boolean): LibraryNotice {
        if (paths.isEmpty()) return LibraryNotice.BatchApplied(count = 0, skipped = 0)
        val byPath = repository.observeLibrary().first().associateBy { it.path }
        val stored = progressDao.observeAll().first().associateBy { it.bookId }
        val known = paths.mapNotNull { byPath[it] }
        val plan = planSetRead(
            targets = known.map { book ->
                ReadTarget(
                    bookId = book.contentKey,
                    scannedPageCount = book.pageCount,
                    storedPageCount = stored[book.contentKey]?.pageCount,
                )
            },
            read = read,
        )
        val stamp = System.currentTimeMillis()
        for (write in plan.writes) {
            progressDao.upsert(ReadingProgress(write.bookId, write.pageIndex, write.pageCount, stamp))
        }
        // A path the library no longer holds is reported, not silently dropped from the count.
        return LibraryNotice.BatchApplied(
            count = plan.writes.size,
            skipped = plan.skipped + (paths.size - known.size),
        )
    }

    override suspend fun delete(paths: Set<String>): LibraryNotice =
        LibraryNotice.BatchUnsupported(UnsupportedReason.DELETE_NOT_IMPLEMENTED)

    private fun LibraryBook.toUi(
        progress: Map<String, ReadingProgress>,
        useOriginalFilename: Boolean,
    ): LibraryBookUi {
        val position = progress[contentKey]
        return LibraryBookUi(
            path = path,
            displayName = displayNameOf(this, useOriginalFilename),
            originalFilename = BookPath.nameOf(path),
            series = series,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            addedAt = addedAt,
            // The scanner leaves a container's count null; a position row knows it once opened.
            pageCount = pageCount ?: position?.pageCount?.takeIf { it > 0 },
            currentPage = position?.pageIndex,
            // TODO(library): read from a real favourites store; see setFavorite.
            isFavorite = false,
        )
    }

    private companion object {

        /**
         * Rebuilds the scanner's [ParsedName] so the library labels a book exactly as the rest of
         * the app does. The persisted columns are that parse, taken apart by Room; putting them
         * back together beats a second copy of the formatting rules that would drift from it.
         *
         * §5.1's "use original filename" switch short-circuits the rebuild: the raw filename is
         * the label for both display and search, which is what the switch's "escape hatch" means.
         */
        fun displayNameOf(book: LibraryBook, useOriginalFilename: Boolean): String {
            if (useOriginalFilename) return BookPath.nameOf(book.path)
            return ParsedName(
                series = book.series,
                issue = book.issue?.let { value -> IssueNumber(value, book.issueRaw ?: value.toString()) },
                volume = book.volume,
                year = book.year,
                title = book.title,
                originalFilename = BookPath.nameOf(book.path),
            ).displayName
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LibraryFeedModule {

    /**
     * The screens depend on [LibraryFeed], never on Room. That is what lets the state layer stay
     * plain Kotlin, and what lets tests hand the ViewModel a fake without a database.
     */
    @Binds
    abstract fun bindLibraryFeed(impl: RoomLibraryFeed): LibraryFeed
}
