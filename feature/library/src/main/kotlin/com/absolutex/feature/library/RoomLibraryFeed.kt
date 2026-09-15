package com.absolutex.feature.library

import com.absolutex.core.data.LibraryBook
import com.absolutex.core.data.LibraryRepository
import com.absolutex.model.IssueNumber
import com.absolutex.model.ParsedName
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibraryFeed] over the shipped repository.
 *
 * ## Why no book here has a reading position
 *
 * `ReadingProgress` is keyed by `uri.toString()` — `file:///…` for a book opened by path,
 * `content://…` for one opened through the picker — while the library keys books by the
 * filesystem path the scanner walked. There is no identity both sides agree on, so this feed does
 * not attempt the join: [LibraryBookUi.currentPage] is always null, every book reads as unread,
 * and the Reading shelf is empty.
 *
 * That is deliberate and was agreed with the lane that owns progress: a book needs one identity
 * chosen at the source, and a mapping invented here would be undone by that fix.
 *
 * TODO(reader/core-data): give a book one identity both sides share, then populate `currentPage`
 *  in [toUi] and turn [LibraryCapabilities.canMarkRead] back on. Nothing else in this module has
 *  to change — the UI already renders positions and progress bars whenever they are present.
 */
@Singleton
internal class RoomLibraryFeed @Inject constructor(
    private val repository: LibraryRepository,
) : LibraryFeed {

    override val capabilities = LibraryCapabilities(
        // Nothing stores a favourite: no column, no table, no preference. See setFavorite.
        canFavorite = false,
        // Writing a position needs the same book identity the join above lacks; rows written
        // under a guessed key would be orphaned by the real fix. See setRead.
        canMarkRead = false,
        // No repository delete exists, and inventing one that removes a user's files from disk
        // is not a call this lane should make. See delete.
        canDelete = false,
    )

    override fun observeBooks(): Flow<List<LibraryBookUi>> =
        repository.observeLibrary()
            .map { books -> books.map { it.toUi() } }
            // Mapping thousands of rows is real work and Room emits on its own executor; Default
            // keeps it off both the main thread and Room's.
            .flowOn(Dispatchers.Default)

    override suspend fun search(query: String): List<LibraryBookUi> =
        repository.search(query).map { it.toUi() }

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

    override suspend fun setRead(paths: Set<String>, read: Boolean): BatchOutcome =
        BatchOutcome.Unsupported(
            "Reading position is keyed differently from the library, so a book written here " +
                "would not be found again. Waiting on one shared book identity.",
        )

    override suspend fun delete(paths: Set<String>): BatchOutcome = BatchOutcome.Unsupported(
        "Deleting a book has to remove it from disk, and no repository operation does that yet.",
    )

    private fun LibraryBook.toUi(): LibraryBookUi = LibraryBookUi(
        path = path,
        displayName = displayNameOf(this),
        originalFilename = File(path).name,
        series = series,
        sizeBytes = sizeBytes,
        lastModified = lastModified,
        addedAt = addedAt,
        pageCount = pageCount,
        // TODO(reader/core-data): see the class comment — no shared identity with progress yet.
        currentPage = null,
        // TODO(library): read from a real favourites store; see setFavorite.
        isFavorite = false,
    )

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
