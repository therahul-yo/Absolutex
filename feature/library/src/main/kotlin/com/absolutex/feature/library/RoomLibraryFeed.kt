package com.absolutex.feature.library

import com.absolutex.core.data.AbsolutexDatabase
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibraryFeed] over the shipped repository and progress table.
 *
 * ## The one thing to know before trusting the Reading shelf
 *
 * `ReadingProgress.bookId` is whatever the reader was handed — `uri.toString()`. A book opened
 * from a file path stores `file:///storage/...`; the same book opened through the SAF picker
 * stores `content://...`. The library, meanwhile, knows books by filesystem path, because that is
 * what the scanner walks. So the two tables key on different things and only the `file://` case
 * joins.
 *
 * The consequence is visible, not silent: a book opened through the picker shows as Unread until
 * it is opened by path. Papering over it with a filename match would be worse — it would
 * confidently attach one book's position to another's.
 *
 * TODO(library): give a book one identity both sides agree on — a content key on
 *  `ReadingProgress`, or a resolved path stored alongside the Uri — and delete [progressKeyFor].
 */
@Singleton
internal class RoomLibraryFeed @Inject constructor(
    database: AbsolutexDatabase,
    private val progressDao: ProgressDao,
) : LibraryFeed {

    /**
     * Constructed, not injected.
     *
     * `LibraryRepository`'s `@Inject` constructor takes `now: () -> Long` with a default, and
     * Dagger cannot satisfy a `Function0<Long>` binding — defaults are invisible to it, so
     * injecting the repository fails at compile time. Building it here uses the defaults and
     * leaves the graph alone.
     * TODO(core-data): drop the defaulted parameters from the `@Inject` constructor (or bind
     *  them) so the repository is injectable like everything else.
     */
    private val repository = LibraryRepository(database.libraryDao())

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
            // One pass to index positions, then a lookup per book: the alternative is a linear
            // scan of the progress table per row, which is quadratic on a large library.
            val byKey = progress.associateBy { it.bookId }
            books.map { it.toUi(byKey) }
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
     * Writes a reading position that puts each book at the requested end of the book.
     *
     * Marking unread always works: position zero is unambiguous. Marking read needs to know where
     * the end is, and a container's page count is unknown until it is opened — those are counted
     * as skipped rather than guessed at.
     */
    override suspend fun setRead(paths: Set<String>, read: Boolean): BatchOutcome {
        if (paths.isEmpty()) return BatchOutcome.Applied(count = 0, skipped = 0)
        val byPath = repository.observeLibrary().first().associateBy { it.path }
        val known = paths.mapNotNull { byPath[it] }
        // Marking unread is always possible — position zero is unambiguous. Marking read needs
        // to know where the end is, and a container's page count stays null until it is opened.
        val (actionable, unknownLength) = known.partition { !read || (it.pageCount ?: 0) > 0 }
        val stamp = System.currentTimeMillis()
        for (book in actionable) {
            val pages = book.pageCount ?: 0
            progressDao.upsert(
                ReadingProgress(
                    bookId = progressKeyFor(book.path),
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
        val position = progress[progressKeyFor(path)]
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
         * The `bookId` the reader would have stored for a book opened at this path.
         *
         * `android.net.Uri.fromFile` renders `file:///a/b` — three slashes. `java.io.File.toURI`
         * renders `file:/a/b` and would never match, which is exactly the sort of near-miss that
         * looks like "progress just doesn't work".
         */
        fun progressKeyFor(path: String): String = "file://$path"

        /**
         * Rebuilds the scanner's [ParsedName] so the library labels a book exactly as the rest of
         * the app does. The persisted columns are that parse, taken apart by Room; putting them
         * back together beats a second copy of the formatting rules that would drift from it.
         *
         * TODO(library): honour [com.absolutex.model.TitlePolicy.ORIGINAL_FILENAME], the §5.1
         *  global switch for turning filename parsing off. It needs a settings store, which does
         *  not exist yet.
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
