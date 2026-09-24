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
    /** False on a fresh install: no storage location has been added yet. */
    val hasLocations: Boolean,
    val capabilities: LibraryCapabilities,
    val error: LibraryNotice?,
    /** One-shot feedback from a batch action; cleared once shown. */
    val message: LibraryNotice?,
    /**
     * The current section, in the current order.
     *
     * A field rebuilt only by [recomputed], not a computed property. `by lazy` reads as equivalent
     * and is not: every `copy()` makes a new instance, so ticking one checkbox — or typing one
     * letter — re-filtered and re-sorted the whole library on the main thread. On 5,000 books that
     * is a full sort per long-press.
     */
    val visibleBooks: List<LibraryBookUi>,
) {

    val selectionActive: Boolean get() = selected.isNotEmpty()

    val selectedCount: Int get() = selected.size

    val emptyReason: LibraryEmptyReason
        get() = when {
            // A read failure is its own message; saying "no folders yet" over it would be a lie.
            loading || error != null -> LibraryEmptyReason.NONE
            visibleBooks.isNotEmpty() -> LibraryEmptyReason.NONE
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
            layout = BrowseLayout.GRID,
            grid = GridSpec.Default,
            section = HomeSection.COMICS,
            selected = emptySet(),
            hasLocations = true,
            capabilities = LibraryCapabilities.None,
            error = null,
            message = null,
            visibleBooks = emptyList(),
        )
    }
}

/**
 * Rebuilds everything derived from [LibraryUiState.allBooks], [LibraryUiState.section] and
 * [LibraryUiState.sort].
 *
 * The only thing that rebuilds them, and the only three things that have to call it: a selection
 * toggle, a layout change or a grid-column change must not pay for a re-sort of the library.
 */
internal fun LibraryUiState.recomputed(): LibraryUiState {
    // A search looks through the whole library: a match hiding in another tab read as "no matches".
    val inSection = if (query.isNotBlank()) allBooks else allBooks.inSection(section)
    // Recent is a history, so it runs newest first whatever the sort chips say.
    val visible = if (section == HomeSection.RECENT) {
        inSection.sortedByDescending { it.lastReadAt ?: 0L }
    } else {
        inSection.sortedBy(sort)
    }
    return copy(visibleBooks = visible)
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
        // interleaves every double-digit issue with the single digits. By the title on screen,
        // not the filename: a SAF book's "filename" is a numeric document id, and ordering by it
        // looked random.
        SortKey.NAME -> sortedWith(
            compareBy<LibraryBookUi, String>(NaturalOrder) { it.displayName }
                .thenBy(NaturalOrder) { it.originalFilename },
        )
        SortKey.SIZE -> sortedBy { it.sizeBytes }
        SortKey.DATE -> sortedBy { it.date }
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

/** The subset a home section shows: comics and books split by format, recent is anything opened. */
internal fun List<LibraryBookUi>.inSection(section: HomeSection): List<LibraryBookUi> =
    when (section) {
        HomeSection.COMICS -> filter { !it.isBook }
        HomeSection.BOOKS -> filter { it.isBook }
        HomeSection.RECENT -> filter { it.readState != ReadState.UNREAD }
        HomeSection.FAVORITES -> filter { it.isFavorite }
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
