package com.absolutex.feature.library

import com.absolutex.core.scan.SortKey
import com.absolutex.source.NaturalOrder

/**
 * Why the screen has nothing to show.
 *
 * Modelled rather than inferred at the call site, because "no locations configured" and "this
 * shelf happens to be empty" need completely different words, and a fresh install hits the first
 * one — §5.1 says it has to tell the user what to do next rather than showing a blank page.
 */
internal enum class LibraryEmptyReason { NONE, NO_LOCATIONS, LIBRARY_EMPTY, NO_SEARCH_MATCHES, SECTION_EMPTY }

/**
 * Everything the library screens render from.
 *
 * Plain Kotlin on purpose: no Compose, no Android, no Room. The interesting logic here is
 * filtering, ordering and selection, and keeping it free of the Android toolchain means it can be
 * unit-tested directly instead of through a UI test on a device.
 *
 * [allBooks] is already filtered by [query] — the search runs in the repository, not here (see
 * [LibraryFeed.search]).
 */
internal data class LibraryUiState(
    val loading: Boolean,
    val allBooks: List<LibraryBookUi>,
    val query: String,
    val sort: SortSpec,
    val layout: BrowseLayout,
    val grid: GridSpec,
    val section: HomeSection,
    val selected: Set<String>,
    /** False on a fresh install: no storage locations have been added yet. */
    val hasLocations: Boolean,
    val capabilities: LibraryCapabilities,
    val error: String?,
    /** One-shot feedback from a batch action; cleared once shown. */
    val message: String?,
) {

    /**
     * The books the current section shows, in the current order.
     *
     * `by lazy`, not `get()`: Compose reads this on every recomposition and a 5,000-book sort per
     * frame is exactly the kind of thing that turns a scroll janky. A new state instance means a
     * new sort, which is precisely when it should happen.
     */
    val visibleBooks: List<LibraryBookUi> by lazy { allBooks.inSection(section).sortedBy(sort) }

    /** Series shelves. Books whose series never parsed fall back to their folder, as the index does. */
    val seriesShelves: List<Shelf> by lazy {
        visibleBooks.groupBy { it.series ?: it.folderName }
            .map { (name, books) -> Shelf(name, books) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
    }

    val folderShelves: List<Shelf> by lazy {
        visibleBooks.groupBy { it.folderName }
            .map { (name, books) -> Shelf(name, books) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
    }

    val selectionActive: Boolean get() = selected.isNotEmpty()

    val selectedCount: Int get() = selected.size

    val emptyReason: LibraryEmptyReason
        get() = when {
            loading || visibleBooks.isNotEmpty() -> LibraryEmptyReason.NONE
            !hasLocations -> LibraryEmptyReason.NO_LOCATIONS
            query.isNotBlank() -> LibraryEmptyReason.NO_SEARCH_MATCHES
            allBooks.isEmpty() -> LibraryEmptyReason.LIBRARY_EMPTY
            else -> LibraryEmptyReason.SECTION_EMPTY
        }

    companion object {
        /** Opening state: loading, nothing known yet, not yet proven to have no locations. */
        val Initial = LibraryUiState(
            loading = true,
            allBooks = emptyList(),
            query = "",
            sort = SortSpec(SortKey.NAME, ascending = true),
            layout = BrowseLayout.DETAILED_LIST,
            grid = GridSpec.Default,
            section = HomeSection.READING,
            selected = emptySet(),
            hasLocations = true,
            capabilities = LibraryCapabilities.None,
            error = null,
            message = null,
        )
    }
}

/**
 * Orders books the way `LibraryIndex.sort` orders a scan.
 *
 * Deliberately a second implementation rather than a call into it: `LibraryIndex.sort` takes
 * `ScannedBook` and this screen renders the persisted `LibraryBook`, and widening that shipped,
 * tested signature from another lane mid-flight is a worse trade than thirteen lines here. The
 * semantics are kept identical on purpose — TODO(library): fold the two together behind one
 * comparator once the lanes converge.
 *
 * One deliberate difference: date comes from the persisted `lastModified` rather than
 * `File(path).lastModified()`, so ordering a 5,000-book library costs no filesystem calls.
 */
internal fun List<LibraryBookUi>.sortedBy(spec: SortSpec): List<LibraryBookUi> {
    val ordered = when (spec.key) {
        // Natural order, so "Issue 2" precedes "Issue 10" — sorting these as plain strings
        // interleaves every double-digit issue with the single digits.
        SortKey.NAME -> sortedWith(compareBy(NaturalOrder) { it.originalFilename })
        SortKey.SIZE -> sortedBy { it.sizeBytes }
        SortKey.DATE -> sortedBy { it.lastModified }
    }
    return if (spec.ascending) ordered else ordered.reversed()
}

/**
 * Picks a sort field, flipping direction when the field is already active.
 *
 * Lives here rather than in the ViewModel so the behaviour every file browser has — tap the
 * column you are already sorted by to reverse it — is covered by a test.
 */
internal fun SortSpec.select(key: SortKey): SortSpec =
    if (this.key == key) copy(ascending = !ascending) else SortSpec(key, ascending = true)

/** The subset a home section shows. SERIES and FOLDERS show everything, grouped by the screen. */
internal fun List<LibraryBookUi>.inSection(section: HomeSection): List<LibraryBookUi> =
    when (section) {
        HomeSection.READING -> filter { it.readState == ReadState.IN_PROGRESS }
        HomeSection.UNREAD -> filter { it.readState == ReadState.UNREAD }
        HomeSection.FAVORITES -> filter { it.isFavorite }
        HomeSection.SERIES, HomeSection.FOLDERS -> this
    }

internal fun LibraryUiState.toggleSelection(path: String): LibraryUiState =
    copy(selected = if (path in selected) selected - path else selected + path)

internal fun LibraryUiState.clearSelection(): LibraryUiState =
    if (selected.isEmpty()) this else copy(selected = emptySet())

/**
 * Selects everything currently on screen — not everything in the library. Selecting rows the user
 * cannot see and then offering to delete them is how people lose files.
 */
internal fun LibraryUiState.selectAllVisible(): LibraryUiState =
    copy(selected = visibleBooks.mapTo(LinkedHashSet(visibleBooks.size)) { it.path })
