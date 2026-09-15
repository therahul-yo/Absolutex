package com.absolutex.feature.library

import com.absolutex.core.scan.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun b(
    path: String,
    series: String? = "S",
    currentPage: Int? = null,
    pageCount: Int? = 20,
    isFavorite: Boolean = false,
) = LibraryBookUi(
    path = path,
    displayName = path.substringAfterLast('/'),
    originalFilename = path.substringAfterLast('/'),
    series = series,
    sizeBytes = 1,
    lastModified = 1,
    addedAt = 1,
    pageCount = pageCount,
    currentPage = currentPage,
    isFavorite = isFavorite,
)

private fun st(books: List<LibraryBookUi>, section: HomeSection) =
    LibraryUiState.Initial.copy(loading = false, allBooks = books, section = section).recomputed()

class SectionFilterTest {

    private val unread = b("/x/unread.cbz", currentPage = null)
    private val reading = b("/x/reading.cbz", currentPage = 5)
    private val finished = b("/x/finished.cbz", currentPage = 19)
    private val favourite = b("/x/fav.cbz", currentPage = 5, isFavorite = true)
    private val all = listOf(unread, reading, finished, favourite)

    @Test fun `reading shows only in-progress books`() {
        assertEquals(
            listOf("reading.cbz", "fav.cbz").sorted(),
            all.inSection(HomeSection.READING).map { it.originalFilename }.sorted(),
        )
    }

    @Test fun `unread excludes finished books`() {
        assertEquals(listOf("unread.cbz"), all.inSection(HomeSection.UNREAD).map { it.originalFilename })
    }

    @Test fun `favorites is independent of read state`() {
        assertEquals(listOf("fav.cbz"), all.inSection(HomeSection.FAVORITES).map { it.originalFilename })
    }

    @Test fun `series and folders show everything, grouped by the screen not the filter`() {
        assertEquals(all.size, all.inSection(HomeSection.SERIES).size)
        assertEquals(all.size, all.inSection(HomeSection.FOLDERS).size)
    }
}

class ShelfTest {

    @Test fun `books without a parsed series fall back to their folder`() {
        val state = st(
            listOf(
                b("/comics/Batman/one.cbz", series = "Batman"),
                b("/comics/Loose/two.cbz", series = null),
            ),
            HomeSection.SERIES,
        )
        assertEquals(listOf("Batman", "Loose"), state.seriesShelves.map { it.title })
    }

    @Test fun `shelves are ordered naturally`() {
        val state = st(
            listOf(
                b("/c/a.cbz", series = "Volume 10"),
                b("/c/b.cbz", series = "Volume 2"),
            ),
            HomeSection.SERIES,
        )
        assertEquals(listOf("Volume 2", "Volume 10"), state.seriesShelves.map { it.title })
    }

    @Test fun `folder shelves group by parent directory`() {
        val state = st(
            listOf(
                b("/c/Marvel/a.cbz", series = "X"),
                b("/c/Marvel/b.cbz", series = "Y"),
                b("/c/DC/c.cbz", series = "Z"),
            ),
            HomeSection.FOLDERS,
        )
        val shelves = state.folderShelves.associate { it.title to it.size }
        assertEquals(mapOf("DC" to 1, "Marvel" to 2), shelves)
    }

    @Test fun `two folders sharing a leaf name are two shelves, not one`() {
        // The bug: grouping by leaf name merged /Comics/DC/2024 with /Comics/Marvel/2024 into a
        // single "2024" shelf holding books from two unrelated folders.
        val state = st(
            listOf(
                b("/Comics/DC/2024/a.cbz", series = null),
                b("/Comics/Marvel/2024/b.cbz", series = null),
            ),
            HomeSection.FOLDERS,
        )
        val shelves = state.folderShelves
        assertEquals(listOf("2024", "2024"), shelves.map { it.title })
        assertEquals(listOf("/Comics/DC/2024", "/Comics/Marvel/2024"), shelves.map { it.id })
        assertEquals(listOf(1, 1), shelves.map { it.size })
    }

    @Test fun `shelf totals sum member sizes`() {
        val shelf = Shelf("S", listOf(b("/a/1.cbz"), b("/a/2.cbz")))
        assertEquals(2, shelf.size)
        assertEquals(2L, shelf.totalBytes)
    }
}

class SelectionTest {

    private val books = listOf(b("/x/a.cbz", currentPage = 5), b("/x/b.cbz", currentPage = null))

    @Test fun `toggle adds then removes`() {
        var s = st(books, HomeSection.SERIES)
        assertFalse(s.selectionActive)
        s = s.toggleSelection("/x/a.cbz")
        assertTrue(s.selectionActive)
        assertEquals(1, s.selectedCount)
        s = s.toggleSelection("/x/a.cbz")
        assertFalse(s.selectionActive)
    }

    @Test fun `clearing an empty selection returns the same instance`() {
        // Cheap identity check: a no-op must not invalidate Compose state.
        val s = st(books, HomeSection.SERIES)
        assertTrue(s === s.clearSelection())
    }

    @Test fun `select all takes only what is on screen`() {
        // Unread shows one of the two books; select-all must not reach the hidden one, because
        // the next tap might be Delete.
        val s = st(books, HomeSection.UNREAD).selectAllVisible()
        assertEquals(setOf("/x/b.cbz"), s.selected)
    }

    @Test fun `select all on an empty section selects nothing`() {
        val s = st(emptyList(), HomeSection.FAVORITES).selectAllVisible()
        assertTrue(s.selected.isEmpty())
    }
}

class EmptyStateTest {

    private val one = listOf(b("/x/a.cbz"))

    @Test fun `loading reports no empty state`() {
        val s = LibraryUiState.Initial
        assertTrue(s.loading)
        assertEquals(LibraryEmptyReason.NONE, s.emptyReason)
    }

    @Test fun `a fresh install with no locations says so`() {
        val s = st(emptyList(), HomeSection.SERIES).copy(hasLocations = false)
        assertEquals(LibraryEmptyReason.NO_LOCATIONS, s.emptyReason)
    }

    @Test fun `locations but no books is an empty library, not a missing location`() {
        val s = st(emptyList(), HomeSection.SERIES)
        assertEquals(LibraryEmptyReason.LIBRARY_EMPTY, s.emptyReason)
    }

    @Test fun `a query that matches nothing is distinct from an empty library`() {
        val s = st(emptyList(), HomeSection.SERIES).copy(query = "zzz")
        assertEquals(LibraryEmptyReason.NO_SEARCH_MATCHES, s.emptyReason)
    }

    @Test fun `no locations outranks a query`() {
        // Telling someone their search matched nothing is useless when they have added no folders.
        val s = st(emptyList(), HomeSection.SERIES).copy(query = "zzz", hasLocations = false)
        assertEquals(LibraryEmptyReason.NO_LOCATIONS, s.emptyReason)
    }

    @Test fun `a populated library with an empty section reports the section`() {
        val s = st(one, HomeSection.FAVORITES)
        assertEquals(LibraryEmptyReason.SECTION_EMPTY, s.emptyReason)
    }

    @Test fun `books on screen means no empty state`() {
        assertEquals(LibraryEmptyReason.NONE, st(one, HomeSection.SERIES).emptyReason)
    }
}

class GridSpecTest {

    @Test fun `columns are per orientation`() {
        val g = GridSpec(portraitColumns = 3, landscapeColumns = 6)
        assertEquals(3, g.columnsFor(landscape = false))
        assertEquals(6, g.columnsFor(landscape = true))
    }

    @Test fun `setting columns touches only the current orientation`() {
        val g = GridSpec(portraitColumns = 3, landscapeColumns = 6).withColumns(landscape = true, columns = 4)
        assertEquals(3, g.portraitColumns)
        assertEquals(4, g.landscapeColumns)
    }

    @Test fun `columns are clamped rather than rejected`() {
        val g = GridSpec.Default.withColumns(landscape = false, columns = 99)
        assertEquals(GridSpec.MAX_COLUMNS, g.portraitColumns)
        assertEquals(GridSpec.MIN_COLUMNS, GridSpec.Default.withColumns(false, 0).portraitColumns)
    }

    @Test fun `a stored out-of-range value is clamped on read`() {
        // Defends against a persisted preference written by an older or newer build.
        assertEquals(GridSpec.MAX_COLUMNS, GridSpec(99, 99).columnsFor(landscape = false))
    }
}

class VisibleBooksTest {

    @Test fun `visible books are filtered then sorted`() {
        val s = LibraryUiState.Initial.copy(
            loading = false,
            allBooks = listOf(
                b("/x/Issue 10.cbz", currentPage = 5),
                b("/x/Issue 2.cbz", currentPage = 5),
                b("/x/Issue 1.cbz", currentPage = null),
            ),
            section = HomeSection.READING,
            sort = SortSpec(SortKey.NAME, ascending = true),
        ).recomputed()
        assertEquals(listOf("Issue 2.cbz", "Issue 10.cbz"), s.visibleBooks.map { it.originalFilename })
    }
}

/**
 * What [recomputed] rebuilds, and what it must not.
 *
 * The lead's review item 10: `visibleBooks` was `by lazy` on a state instance, so every `copy()` —
 * including one checkbox tap — re-filtered and re-sorted the entire library on the main thread.
 */
class DerivedStateTest {

    private val two = listOf(
        b("/x/Issue 10.cbz", currentPage = 5),
        b("/x/Issue 2.cbz", currentPage = 5),
    )

    private fun state(books: List<LibraryBookUi>) =
        LibraryUiState.Initial.copy(loading = false, allBooks = books, section = HomeSection.SERIES)
            .recomputed()

    @Test fun `ticking a checkbox does not rebuild the sorted list`() {
        val s = state(two)
        val ticked = s.toggleSelection("/x/Issue 2.cbz")
        assertSame(s.visibleBooks, ticked.visibleBooks)
        assertSame(s.seriesShelves, ticked.seriesShelves)
        assertSame(s.folderShelves, ticked.folderShelves)
    }

    @Test fun `changing layout, grid or message does not rebuild either`() {
        val s = state(two)
        assertSame(s.visibleBooks, s.copy(layout = BrowseLayout.GRID).visibleBooks)
        assertSame(s.visibleBooks, s.copy(grid = GridSpec.Default.withColumns(true, 6)).visibleBooks)
        assertSame(
            s.visibleBooks,
            s.copy(message = LibraryNotice.BatchApplied(count = 1, skipped = 0)).visibleBooks,
        )
    }

    @Test fun `a books change only takes effect through recomputed`() {
        val s = state(two)
        val grown = s.copy(allBooks = two + b("/x/Issue 3.cbz", currentPage = 5))
        // Documented, not desired: `copy` keeps the derived lists it was built with, which is why
        // every path that changes the books calls recomputed.
        assertSame(s.visibleBooks, grown.visibleBooks)
        assertEquals(3, grown.recomputed().visibleBooks.size)
    }

    @Test fun `a section change refilters`() {
        val s = state(two + b("/x/Issue 3.cbz", currentPage = null))
        val unread = s.copy(section = HomeSection.UNREAD).recomputed()
        assertEquals(listOf("Issue 3.cbz"), unread.visibleBooks.map { it.originalFilename })
    }

    @Test fun `a sort change reorders`() {
        val s = state(two)
        val descending = s.copy(sort = SortSpec(SortKey.NAME, ascending = false)).recomputed()
        assertEquals(listOf("Issue 2.cbz", "Issue 10.cbz"), s.visibleBooks.map { it.originalFilename })
        assertEquals(listOf("Issue 10.cbz", "Issue 2.cbz"), descending.visibleBooks.map { it.originalFilename })
    }

    @Test fun `an error suppresses the empty state rather than claiming no folders`() {
        // A failed load used to be rendered as "No folders yet", which is a different and false
        // claim about the user's setup.
        val failed = LibraryUiState.Initial.copy(
            loading = false,
            allBooks = emptyList(),
            hasLocations = false,
            error = LibraryNotice.LoadFailed,
        )
        assertEquals(LibraryEmptyReason.NONE, failed.emptyReason)
    }
}
