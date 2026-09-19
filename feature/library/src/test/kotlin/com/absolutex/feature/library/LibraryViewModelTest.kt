package com.absolutex.feature.library

import com.absolutex.core.scan.LibraryChange
import com.absolutex.core.scan.SortKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import org.junit.runner.Description

/**
 * The ViewModel, on the JVM and without a device.
 *
 * [LibraryViewModel] has no Android dependencies of its own — it moves a [LibraryFeed] into
 * [LibraryUiState] — so faking the feed covers the three review items that live here: a stale
 * result set after a batch during a search, a selection surviving a query change, and
 * `hasLocations` being read once. `viewModelScope` needs a main dispatcher, which is all the rule
 * below supplies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** Shares the rule's scheduler, so advancing time in the test drives the ViewModel's scope. */
    private fun test(body: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { body() }

    private fun book(
        path: String,
        series: String? = "Batman",
        pageCount: Int? = null,
        currentPage: Int? = null,
        sizeBytes: Long = 100,
    ) = LibraryBookUi(
        path = path,
        displayName = path.substringAfterLast('/'),
        originalFilename = path.substringAfterLast('/'),
        series = series,
        sizeBytes = sizeBytes,
        lastModified = 0,
        addedAt = 0,
        pageCount = pageCount,
        currentPage = currentPage,
        isFavorite = false,
    )

    @Test
    fun `hasLocations follows the books, so adding a folder stops saying none exist`() = test {
        val feed = FakeFeed()
        val vm = LibraryViewModel(feed)
        advanceUntilIdle()

        feed.emit(emptyList())
        advanceUntilIdle()
        assertFalse("no books at all is the fresh-install state", vm.ui.value.hasLocations)

        feed.emit(listOf(book("/comics/Batman 001.cbz")))
        advanceUntilIdle()
        assertTrue("a scan that lands must show up in the empty state", vm.ui.value.hasLocations)
    }

    @Test
    fun `changing the query clears the selection`() = test {
        val feed = FakeFeed()
        val vm = LibraryViewModel(feed)
        feed.emit(listOf(book("/comics/Batman 001.cbz"), book("/comics/Batman 002.cbz")))
        advanceUntilIdle()

        vm.onToggleSelection("/comics/Batman 002.cbz")
        assertEquals(1, vm.ui.value.selectedCount)

        vm.onQueryChange("001")
        // Cleared before the search even runs: the rows the selection covered may be about to go.
        assertEquals(0, vm.ui.value.selectedCount)
    }

    @Test
    fun `a batch during a search re-runs the query`() = test {
        val feed = FakeFeed()
        val vm = LibraryViewModel(feed)
        feed.emit(listOf(book("/comics/Batman 001.cbz", pageCount = 20, currentPage = 5)))
        advanceUntilIdle()

        vm.onQueryChange("Batman")
        advanceUntilIdle()
        assertEquals(1, feed.searches)

        // Stale rows are the bug: they were answered from the table as it stood before the write,
        // and nothing re-ran the query until the next keystroke.
        vm.onToggleSelection("/comics/Batman 001.cbz")
        vm.onBatch(BatchAction.MARK_READ)
        advanceUntilIdle()

        assertEquals(2, feed.searches)
        assertEquals("the write lands before the re-read", listOf("/comics/Batman 001.cbz"), feed.reads)
    }

    @Test
    fun `a batch reports its outcome and clears the selection`() = test {
        val feed = FakeFeed()
        val vm = LibraryViewModel(feed)
        feed.emit(listOf(book("/comics/Batman 001.cbz")))
        advanceUntilIdle()

        vm.onToggleSelection("/comics/Batman 001.cbz")
        vm.onBatch(BatchAction.MARK_READ)
        advanceUntilIdle()

        assertEquals(LibraryNotice.BatchApplied(count = 1, skipped = 0), vm.ui.value.message)
        assertEquals(0, vm.ui.value.selectedCount)
    }

    @Test
    fun `an action the data layer refuses keeps the selection`() = test {
        val feed = FakeFeed()
        val vm = LibraryViewModel(feed)
        feed.emit(listOf(book("/comics/Batman 001.cbz")))
        advanceUntilIdle()

        vm.onToggleSelection("/comics/Batman 001.cbz")
        vm.onBatch(BatchAction.FAVORITE)
        advanceUntilIdle()

        assertEquals(
            LibraryNotice.BatchUnsupported(UnsupportedReason.FAVOURITES_STORE_MISSING),
            vm.ui.value.message,
        )
        assertEquals("re-picking it would be busywork", 1, vm.ui.value.selectedCount)
    }

    @Test
    fun `a throwing action reports a failure rather than crashing the screen`() = test {
        val feed = FakeFeed()
        feed.onSetRead = { throw IllegalStateException("disk") }
        val vm = LibraryViewModel(feed)
        feed.emit(listOf(book("/comics/Batman 001.cbz")))
        advanceUntilIdle()

        vm.onToggleSelection("/comics/Batman 001.cbz")
        vm.onBatch(BatchAction.MARK_READ)
        advanceUntilIdle()

        assertEquals(LibraryNotice.BatchFailed, vm.ui.value.message)
    }

    @Test
    fun `a failed search surfaces as an error notice, not as an empty library`() = test {
        val feed = FakeFeed()
        val vm = LibraryViewModel(feed)
        feed.emit(listOf(book("/comics/Batman 001.cbz")))
        advanceUntilIdle()

        feed.failNextSearch = true
        vm.onQueryChange("zzz")
        advanceUntilIdle()

        assertEquals(LibraryNotice.LoadFailed, vm.ui.value.error)
    }

    @Test
    fun `a failing books flow surfaces as an error notice`() = test {
        val feed = FakeFeed()
        feed.failFlow = true
        val vm = LibraryViewModel(feed)
        advanceUntilIdle()

        assertEquals(LibraryNotice.LoadFailed, vm.ui.value.error)
        assertFalse(vm.ui.value.loading)
    }

    @Test
    fun `marking unread clears the position through the feed`() = test {
        val feed = FakeFeed()
        val vm = LibraryViewModel(feed)
        feed.emit(listOf(book("/comics/Batman 001.cbz", pageCount = 20, currentPage = 5)))
        advanceUntilIdle()

        vm.onToggleSelection("/comics/Batman 001.cbz")
        vm.onBatch(BatchAction.MARK_UNREAD)
        advanceUntilIdle()

        assertEquals(listOf("/comics/Batman 001.cbz"), feed.reads)
        assertEquals(false, feed.lastReadFlag)
    }

    @Test
    fun `sorting through the ViewModel reorders what is on screen`() = test {
        val feed = FakeFeed()
        val vm = LibraryViewModel(feed)
        feed.emit(listOf(book("/comics/Issue 10.cbz"), book("/comics/Issue 2.cbz")))
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
 * A [LibraryFeed] that records what it was asked to do.
 *
 * Deliberately a fake rather than a Room database: what is under test is the ViewModel's
 * reactions — when it re-reads, what it clears, what it reports — not the SQL underneath, which
 * `:core:data` already covers.
 */
private class FakeFeed(
    caps: LibraryCapabilities = LibraryCapabilities(canFavorite = true, canMarkRead = true, canDelete = true),
) : LibraryFeed {

    private val books = MutableStateFlow<List<LibraryBookUi>>(emptyList())

    override val capabilities: LibraryCapabilities = caps

    /** How many times the query has been run. The stale-results fix is a second run. */
    var searches = 0
        private set

    var failNextSearch = false
    var failFlow = false
    var onSetRead: suspend (Set<String>) -> LibraryNotice = { paths ->
        LibraryNotice.BatchApplied(count = paths.size, skipped = 0)
    }

    /** Every path handed to [setRead], and which way round. */
    val reads = mutableListOf<String>()
    var lastReadFlag: Boolean? = null
        private set

    fun emit(next: List<LibraryBookUi>) {
        books.value = next
    }

    override fun observeBooks(): Flow<List<LibraryBookUi>> =
        if (failFlow) flow { throw IllegalStateException("database unavailable") } else books

    override suspend fun search(query: String): List<LibraryBookUi> {
        searches++
        if (failNextSearch) {
            failNextSearch = false
            throw IllegalStateException("query failed")
        }
        return books.value.filter { it.originalFilename.contains(query, ignoreCase = true) }
    }

    override suspend fun setFavorite(paths: Set<String>, favorite: Boolean): LibraryNotice =
        LibraryNotice.BatchUnsupported(UnsupportedReason.FAVOURITES_STORE_MISSING)

    override suspend fun setRead(paths: Set<String>, read: Boolean): LibraryNotice {
        reads += paths
        lastReadFlag = read
        return onSetRead(paths)
    }

    override suspend fun delete(paths: Set<String>): LibraryNotice =
        LibraryNotice.BatchUnsupported(UnsupportedReason.DELETE_NOT_IMPLEMENTED)
}
