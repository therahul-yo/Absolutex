package com.absolutex.feature.library

import com.absolutex.core.data.LibraryBook
import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibraryFeed] over the shipped repository and the progress table.
 *
 * Progress joins on `LibraryBook.contentKey`, which since 6f7d70d is `BookIdentity.of(name, size)`
 * — the same key `ReadingProgress.bookId` now carries, however the book was opened. Before that
 * the two tables keyed on different things (a Uri string against a filesystem path) and the
 * Reading shelf could not exist; this feed deliberately showed every book as unread rather than
 * inventing a mapping that the real fix would undo.
 */
@Singleton
internal class RoomLibraryFeed @Inject constructor(
    private val repository: LibraryRepository,
    private val progressDao: ProgressDao,
) : LibraryFeed {

    override val capabilities = LibraryCapabilities(
        // Nothing stores a favourite: no column, no table, no preference. See setFavorite.
        canFavorite = false,
        canMarkRead = true,
        // No repository delete exists, and inventing one that removes a user's files from disk
        // is not a call this lane should make. See delete.
        canDelete = false,
    )

    override fun observeBooks(): Flow<List<LibraryBookUi>> =
        combine(repository.observeLibrary(), progressDao.observeAll()) { books, progress ->
            // Index the positions once, then look up per book: scanning the progress table per
            // row instead would be quadratic on a large library.
            val byIdentity = progress.associateBy { it.bookId }
            books.map { it.toUi(byIdentity) }
            // Mapping thousands of rows is real work and Room emits on its own executor; Default
            // keeps it off both the main thread and Room's.
        }.flowOn(Dispatchers.Default)

    override suspend fun search(query: String): List<LibraryBookUi> {
        val progress = progressDao.observeAll().first().associateBy { it.bookId }
        return repository.search(query).map { it.toUi(progress) }
    }

    /**
     * There is no store of configured locations yet, so this answers the question the empty state
     * actually asks — "is there anything to do here?" — using the only signal available.
     *
     * A configured location that genuinely contains no books therefore reads as "no locations".
     * The advice shown is the same either way ("add a folder"), so the wrong branch still gives
     * the right instruction; it is the wording that is imprecise.
     * TODO(library): read this from a real locations store once §5.1 storage locations land.
     */
    override suspend fun hasLocations(): Boolean =
        repository.observeLibrary().first().isNotEmpty()

    override suspend fun setFavorite(paths: Set<String>, favorite: Boolean): BatchOutcome =
        BatchOutcome.Unsupported(
            "Favourites need somewhere to live — no column, table or preference exists yet.",
        )

    /**
     * Writes a position that puts each book at the requested end of it.
     *
     * Marking unread always works: position zero is unambiguous. Marking read needs to know where
     * the end is, and a container's page count is unknown until it is opened — those are counted
     * as skipped rather than guessed at.
     */
    override suspend fun setRead(paths: Set<String>, read: Boolean): BatchOutcome {
        if (paths.isEmpty()) return BatchOutcome.Applied(count = 0, skipped = 0)
        val byPath = repository.observeLibrary().first().associateBy { it.path }
        val known = paths.mapNotNull { byPath[it] }
        val (actionable, unknownLength) = known.partition { !read || (it.pageCount ?: 0) > 0 }
        val stamp = System.currentTimeMillis()
        for (book in actionable) {
            val pages = book.pageCount ?: 0
            progressDao.upsert(
                ReadingProgress(
                    // The library's own identity column, which is what the reader stores too.
                    bookId = book.contentKey,
                    pageIndex = if (read) pages - 1 else 0,
                    pageCount = pages,
                    updatedAt = stamp,
                ),
            )
        }
        val missing = paths.size - known.size
        return BatchOutcome.Applied(count = actionable.size, skipped = unknownLength.size + missing)
    }

    override suspend fun delete(paths: Set<String>): BatchOutcome = BatchOutcome.Unsupported(
        "Deleting a book has to remove it from disk, and no repository operation does that yet.",
    )

    private fun LibraryBook.toUi(progress: Map<String, ReadingProgress>): LibraryBookUi {
        val position = progress[contentKey]
        return LibraryBookUi(
            path = path,
            displayName = displayNameOf(this),
            originalFilename = File(path).name,
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
         * TODO(library): honour [com.absolutex.model.TitlePolicy.ORIGINAL_FILENAME], the §5.1
         *  global switch for turning filename parsing off. It needs a settings store.
         */
        fun displayNameOf(book: LibraryBook): String = ParsedName(
            series = book.series,
            issue = book.issue?.let { value -> IssueNumber(value, book.issueRaw ?: value.toString()) },
            volume = book.volume,
            year = book.year,
            title = book.title,
            originalFilename = File(book.path).name,
        ).displayName
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
