package com.absolutex.feature.library

import com.absolutex.core.scan.LibraryChange
import com.absolutex.core.scan.SortKey
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.AppPrefsSource
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.LibraryBook
import com.absolutex.core.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import java.io.File
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

private fun realFeed(db: AbsolutexDatabase): RealFeed = RealFeed(LibraryRepository(db.libraryDao()))

/**
 * Room's default query/transaction executors are real background threads, which race against
 * the test dispatcher's virtual clock: advanceUntilIdle() can return before a background
 * transaction has posted its resumption back. A same-thread executor makes every DAO call
 * finish before the suspend call returns, which is what advanceUntilIdle() needs to see it.
 */
private fun freshDb(): AbsolutexDatabase {
    val sameThread = Executor { it.run() }
    return Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AbsolutexDatabase::class.java,
    ).setQueryExecutor(sameThread).setTransactionExecutor(sameThread).allowMainThreadQueries().build()
}

/**
 * A real, repeatable way to make the real repository throw: a dropped table gives a genuine
 * [android.database.sqlite.SQLiteException], not a [kotlinx.coroutines.CancellationException] —
 * closing the database instead makes Room's own generated query coroutines throw
 * `JobCancellationException`, which `runCatchingCancellable` (correctly) treats as an ordinary
 * cancellation rather than a failure, so it would never surface as `BatchFailed`/`LoadFailed`.
 */
private fun breakDb(db: AbsolutexDatabase) {
    db.openHelper.writableDatabase.execSQL("DROP TABLE library_book")
}

private fun fixedPrefs(prefs: AppPrefs = AppPrefs()): AppPrefsSource = object : AppPrefsSource {
    private val flow = MutableStateFlow(prefs)
    override val appPrefs: Flow<AppPrefs> = flow
    override suspend fun currentAppPrefs(): AppPrefs = flow.value
}

private fun libraryBook(
    path: String,
    series: String? = "Batman",
    issue: Double? = 1.0,
    sizeBytes: Long = 100,
) = LibraryBook(
    path = path,
    contentKey = "${path.substringAfterLast('/')}:$sizeBytes",
    series = series,
    title = null,
    issue = issue,
    issueRaw = issue?.toInt()?.toString(),
    volume = null,
    year = null,
    sizeBytes = sizeBytes,
    lastModified = 0,
    isImageFolder = false,
    pageCount = null,
    addedAt = 0,
    seenAtScan = 1,
)

/**
 * The ViewModel, on the JVM and without a device, against a real in-memory Room database.
 *
 * [LibraryViewModel] has no Android dependencies of its own — it moves a [LibraryFeed] into
 * [LibraryUiState] — but [LibraryRepository] is a final class with an internal constructor, so it
 * cannot be subclassed or faked; every test here drives it for real, the same way
 * [RoomLibraryFeedTest] does. `viewModelScope` needs a main dispatcher, which is what the rule
 * below supplies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** Shares the rule's scheduler, so advancing time in the test drives the ViewModel's scope. */
    private fun test(body: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { body() }

    private fun vmOver(db: AbsolutexDatabase, prefs: AppPrefsSource = fixedPrefs()) = LibraryViewModel(
        feed = realFeed(db),
        repository = LibraryRepository(db.libraryDao()),
        prefs = prefs,
    )

    @Test
    fun `hasLocations follows the books, so adding a folder stops saying none exist`() = test {
        val db = freshDb()
        val vm = vmOver(db)
        advanceUntilIdle()
        assertFalse("no books at all is the fresh-install state", vm.ui.value.hasLocations)

        db.libraryDao().upsertAll(listOf(libraryBook("/comics/Batman 001.cbz")))
        advanceUntilIdle()
        assertTrue("a scan that lands must show up in the empty state", vm.ui.value.hasLocations)
    }

    @Test
    fun `changing the query clears the selection`() = test {
        val db = freshDb()
        db.libraryDao().upsertAll(
            listOf(libraryBook("/comics/Batman 001.cbz"), libraryBook("/comics/Batman 002.cbz")),
        )
        val vm = vmOver(db)
        advanceUntilIdle()

        vm.onToggleSelection("/comics/Batman 002.cbz")
        assertEquals(1, vm.ui.value.selectedCount)

        vm.onQueryChange("001")
        // Cleared before the search even runs: the rows the selection covered may be about to go.
        assertEquals(0, vm.ui.value.selectedCount)
    }

    @Test
    fun `a batch reports its outcome, clears the selection and writes through`() = test {
        val db = freshDb()
        db.libraryDao().upsertAll(listOf(libraryBook("/comics/Batman 001.cbz")))
        val vm = vmOver(db)
        advanceUntilIdle()

        vm.onToggleSelection("/comics/Batman 001.cbz")
        vm.onBatch(BatchAction.FAVORITE)
        advanceUntilIdle()

        assertEquals(LibraryNotice.BatchApplied(count = 1, skipped = 0), vm.ui.value.message)
        assertEquals(0, vm.ui.value.selectedCount)
        assertTrue(
            "the batch must reach the real repository, not just report success",
            db.libraryDao().observeAll().first().single().isFavorite,
        )
    }

    @Test
    fun `a batch with nothing selected is a no-op`() = test {
        val db = freshDb()
        db.libraryDao().upsertAll(listOf(libraryBook("/comics/Batman 001.cbz")))
        val vm = vmOver(db)
        advanceUntilIdle()

        vm.onBatch(BatchAction.FAVORITE)
        advanceUntilIdle()

        assertEquals("no selection means nothing to report", null, vm.ui.value.message)
        assertFalse(
            "an empty selection must not touch the repository",
            db.libraryDao().observeAll().first().single().isFavorite,
        )
    }

    @Test
    fun `a throwing action reports a failure rather than crashing the screen`() = test {
        val db = freshDb()
        db.libraryDao().upsertAll(listOf(libraryBook("/comics/Batman 001.cbz")))
        val vm = vmOver(db)
        advanceUntilIdle()

        vm.onToggleSelection("/comics/Batman 001.cbz")
        breakDb(db)
        vm.onBatch(BatchAction.FAVORITE)
        advanceUntilIdle()

        assertEquals(LibraryNotice.BatchFailed, vm.ui.value.message)
    }

    @Test
    fun `a failed search surfaces as an error notice, not as an empty library`() = test {
        val db = freshDb()
        db.libraryDao().upsertAll(listOf(libraryBook("/comics/Batman 001.cbz")))
        val vm = vmOver(db)
        advanceUntilIdle()

        breakDb(db)
        vm.onQueryChange("zzz")
        advanceUntilIdle()

        assertEquals(LibraryNotice.LoadFailed, vm.ui.value.error)
    }

    @Test
    fun `a failing books flow surfaces as an error notice`() = test {
        val db = freshDb()
        // Broken before the ViewModel is even built: the very first collection of observeBooks()
        // must hit the fault, exercising the same catch{} path a real query failure would.
        breakDb(db)
        val vm = vmOver(db)
        advanceUntilIdle()

        assertEquals(LibraryNotice.LoadFailed, vm.ui.value.error)
        assertFalse(vm.ui.value.loading)
    }

    @Test
    fun `marking unread is delegated to the feed and reported as applied`() = test {
        val db = freshDb()
        db.libraryDao().upsertAll(listOf(libraryBook("/comics/Batman 001.cbz")))
        val vm = vmOver(db)
        advanceUntilIdle()

        vm.onToggleSelection("/comics/Batman 001.cbz")
        vm.onBatch(BatchAction.MARK_UNREAD)
        advanceUntilIdle()

        assertEquals(LibraryNotice.BatchApplied(count = 1, skipped = 0), vm.ui.value.message)
    }

    @Test
    fun `sorting through the ViewModel reorders what is on screen`() = test {
        val db = freshDb()
        db.libraryDao().upsertAll(
            listOf(libraryBook("/comics/Issue 10.cbz", issue = 10.0), libraryBook("/comics/Issue 2.cbz", issue = 2.0)),
        )
        val vm = vmOver(db)
        advanceUntilIdle()

        vm.onSectionChange(HomeSection.SERIES)
        advanceUntilIdle()
        assertEquals(
            listOf("Issue 2.cbz", "Issue 10.cbz"),
            vm.ui.value.visibleBooks.map { it.originalFilename },
        )

        // The opening sort is already by name, so picking Name reverses it — the behaviour every
        // file browser has, and the reason SortSpec.select is tested separately.
        vm.onSortChange(SortKey.NAME)
        advanceUntilIdle()
        assertEquals(
            listOf("Issue 10.cbz", "Issue 2.cbz"),
            vm.ui.value.visibleBooks.map { it.originalFilename },
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/**
 * A [LibraryFeed] backed by a real in-memory Room database, matching [RoomLibraryFeedTest].
 * Asserting on rows (not just event counts) requires the real repository — a fake feed misses
 * persistence-level behaviour the ViewModel relies on (§5.1).
 */
private class RealFeed(val repository: LibraryRepository) : LibraryFeed {
    override val capabilities = LibraryCapabilities(
        canFavorite = true,
        canMarkRead = true,
        canDelete = true,
    )

    override fun observeBooks(): kotlinx.coroutines.flow.Flow<List<LibraryBookUi>> =
        repository.observeLibrary().map { list ->
            list.map { book ->
                LibraryBookUi(
                    path = book.path,
                    displayName = book.series ?: book.path.substringAfterLast('/'),
                    originalFilename = book.path.substringAfterLast('/'),
                    series = book.series,
                    sizeBytes = book.sizeBytes,
                    lastModified = book.lastModified,
                    addedAt = book.addedAt,
                    pageCount = book.pageCount ?: 0,
                    currentPage = null,
                    isFavorite = book.isFavorite ?: false,
                )
            }
        }

    override suspend fun search(query: String): List<LibraryBookUi> {
        return repository.search(query).map { book ->
            LibraryBookUi(
                path = book.path,
                displayName = book.series ?: book.path.substringAfterLast('/'),
                originalFilename = book.path.substringAfterLast('/'),
                series = book.series,
                sizeBytes = book.sizeBytes,
                lastModified = book.lastModified,
                addedAt = book.addedAt,
                pageCount = book.pageCount ?: 0,
                currentPage = null,
                isFavorite = book.isFavorite ?: false,
            )
        }
    }

    override suspend fun setFavorite(paths: Set<String>, favorite: Boolean): LibraryNotice {
        paths.forEach { repository.upsertFavorite(it, favorite) }
        return LibraryNotice.BatchApplied(count = paths.size, skipped = 0)
    }

    override suspend fun setRead(paths: Set<String>, read: Boolean): LibraryNotice {
        paths.forEach { repository.applyChange(LibraryChange.Modified(it), File(it)) }
        return LibraryNotice.BatchApplied(count = paths.size, skipped = 0)
    }

    override suspend fun delete(paths: Set<String>): LibraryNotice {
        paths.forEach { repository.applyChange(LibraryChange.Removed(it), File(it)) }
        return LibraryNotice.BatchApplied(count = paths.size, skipped = 0)
    }
}
