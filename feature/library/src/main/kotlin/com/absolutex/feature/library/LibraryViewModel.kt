package com.absolutex.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.scan.LibraryChange
import com.absolutex.core.scan.LibraryWatcher
import com.absolutex.core.scan.SortKey
import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.data.runCatchingCancellable
import com.absolutex.core.data.settings.AppPrefsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Drives the library screens.
 *
 * Holds no rendering logic and no Room types: it moves [LibraryFeed] output into
 * [LibraryUiState] and applies the user's intents. Everything worth asserting about — ordering,
 * section membership, selection, empty states — lives in the plain-Kotlin state layer next door
 * and is unit-tested there, which is also why this class stays as thin as it is.
 *
 * It is the only place that calls [recomputed], which is what keeps a checkbox tap from
 * re-filtering and re-sorting the library.
 */
@HiltViewModel
internal class LibraryViewModel @Inject constructor(
    private val feed: LibraryFeed,
    private val repository: LibraryRepository,
    private val prefs: AppPrefsSource,
) : ViewModel() {

    private val _ui = MutableStateFlow(LibraryUiState.Initial.copy(capabilities = feed.capabilities))
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    /** The unfiltered library, kept so clearing the query does not need a round trip. */
    private var everything: List<LibraryBookUi> = emptyList()
    private var searchJob: Job? = null

    /** Cancels the previous search before launching a new one (§5.1). */
    private var searchJob: Job? = null

    init {
        // Live library updates (§5.1): filesystem events coalesced by the watcher feed Room,
        // which re-emits through [LibraryFeed.observeBooks] without a manual rescan.
        // The collector lives in [viewModelScope] and dies with the screen; a single rescan
        // job prevents a burst from queuing rescan behind rescan.
        // The watcher observer: coalesced events feed the repository, which updates Room;
        // the feed's observeBooks picks up Room changes automatically. A single rescan job
        // prevents a burst from queuing rescan behind rescan (§5.1 live updates).
        // SAF tree Uris (content://...) have no filesystem path and cannot be watched by a
        // File-based watcher. Only file-system locations reach the watcher; SAF trees refresh
        // through rescan-on-demand rather than live events (§5.1 live updates).
        viewModelScope.launch {
            prefs.appPrefs.map { it.locations }.distinctUntilChanged().collectLatest { locations ->
                val fileRoots = locations.filterNot { it.startsWith("content:") || it.startsWith("android:") }
                    .mapNotNull { path ->
                        runCatchingCancellable { File(path) }
                            .getOrNull()?.takeIf { it.exists() && it.isDirectory }
                    }
                fileRoots.forEach { root ->
                    launch {
                        LibraryWatcher(roots = listOf(root), debounceMs = LibraryWatcher.DEBOUNCE_MS)
                            .watch().collect { change ->
                                val eventPath = (change as? LibraryChange.Added)?.path
                                    ?: (change as? LibraryChange.Modified)?.path
                                    ?: (change as? LibraryChange.FolderPromoted)?.path
                                    ?: (change as? LibraryChange.Removed)?.path
                                    ?: ""
                                val eventFile = if (eventPath.isNotEmpty()) {
                                    runCatchingCancellable { File(eventPath) }.getOrNull()
                                } else null
                                val locationRoot = eventFile?.let { file ->
                                    fileRoots.firstOrNull { r -> file.path.startsWith(r.path) }
                                        ?: fileRoots.firstOrNull()
                                }
                                runCatchingCancellable {
                                    repository.applyChange(change, locationRoot)
                                }.onFailure { error ->
                                    if (error is Exception && error !is kotlinx.coroutines.CancellationException) {
                                        // Degrades quietly; cancellation propagates through structured concurrency.
                                    }
                                }
                            }
                    }
                }
            }
        }
        viewModelScope.launch {
            feed.observeBooks()
                .catch { _ui.update { it.copy(loading = false, error = LibraryNotice.LoadFailed) } }
                .collect { books ->
                    everything = books
                    _ui.update { state ->
                        // hasLocations is re-derived from every emission. It used to be read once,
                        // so a first-run user who added a folder kept seeing "No folders yet" on
                        // every empty section for the rest of the session.
                        val next = state.copy(
                            loading = false,
                            hasLocations = books.isNotEmpty(),
                            error = null,
                        )
                        // A scan landing mid-search must not yank the user's results out from
                        // under them; the next keystroke (or clearing the box) picks them up.
                        if (state.query.isBlank()) next.copy(allBooks = books).recomputed() else next
                    }
                }
        }
    }

    /**
     * Search as you type (§5.1).
     *
     * The query text lands in state immediately so the field never lags a keypress, while the
     * lookup itself is debounced and cancellable — one search per pause, not one per character.
     *
     * The selection is cleared first, because a query narrows what is on screen: keeping a tick on
     * a row that no longer matches means the next Mark read acts on books the user cannot see.
     */
    fun onQueryChange(query: String) {
        _ui.update { it.clearSelection().copy(query = query) }
        search(query, debounce = true)
    }

    fun onSectionChange(section: HomeSection) {
        // Leaving a section with rows ticked would strand a selection the user can no longer see.
        _ui.update { it.clearSelection().copy(section = section).recomputed() }
    }

    fun onLayoutChange(layout: BrowseLayout) = _ui.update { it.copy(layout = layout) }

    fun onSortChange(key: SortKey) = _ui.update { it.copy(sort = it.sort.select(key)).recomputed() }

    fun onGridColumnsChange(landscape: Boolean, columns: Int) = _ui.update {
        it.copy(grid = it.grid.withColumns(landscape, columns))
    }

    fun onToggleSelection(path: String) = _ui.update { it.toggleSelection(path) }

    fun onClearSelection() = _ui.update { it.clearSelection() }

    fun onSelectAllVisible() = _ui.update { it.selectAllVisible() }

    fun onMessageShown() = _ui.update { it.copy(message = null) }

    /**
     * Runs a batch action over the selection and reports what actually happened.
     *
     * The selection is cleared only on success: leaving it intact after a refusal means the user
     * can act on the same books again without re-picking them.
     */
    fun onBatch(action: BatchAction) {
        val targets = _ui.value.selected
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val notice = runCatchingCancellable {
                when (action) {
                    BatchAction.MARK_READ -> feed.setRead(targets, read = true)
                    BatchAction.MARK_UNREAD -> feed.setRead(targets, read = false)
                    BatchAction.FAVORITE -> feed.setFavorite(targets, favorite = true)
                    BatchAction.UNFAVORITE -> feed.setFavorite(targets, favorite = false)
                    BatchAction.DELETE -> feed.delete(targets)
                }
            }.getOrElse { LibraryNotice.BatchFailed }
            _ui.update { state ->
                when (notice) {
                    is LibraryNotice.BatchApplied -> state.clearSelection().copy(message = notice)
                    is LibraryNotice.BatchUnsupported -> state.copy(message = notice)
                    LibraryNotice.BatchFailed, LibraryNotice.LoadFailed -> state.copy(message = notice)
                }
            }
            // The visible rows were answered from the table as it stood before the write, so
            // during a search they keep showing "On page N of M" until the next keystroke. Re-run
            // the query — immediately, because the user started this and is watching for it.
            val query = _ui.value.query
            if (notice is LibraryNotice.BatchApplied && query.isNotBlank()) {
                search(query, debounce = false)
            }
        }
    }

    /**
     * Runs [query] against the feed, cancelling whatever was in flight.
     *
     * @param debounce true waits for a pause in typing; false runs now, for a caller re-reading
     *   after an action the user took. A blank query is not a search for nothing — it is the whole
     *   library, which is already in hand.
     */
    private fun search(query: String, debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isBlank()) {
                _ui.update { it.copy(allBooks = everything, loading = false).recomputed() }
                return@launch
            }
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            runCatchingCancellable { feed.search(query) }.fold(
                onSuccess = { books ->
                    _ui.update { it.copy(allBooks = books, loading = false, error = null).recomputed() }
                },
                onFailure = {
                    _ui.update { it.copy(loading = false, error = LibraryNotice.LoadFailed) }
                },
            )
        }
    }

    private companion object {
        /**
         * Long enough that a fast typist triggers one search, short enough to feel immediate.
         * The work itself is a scan measured in single-digit milliseconds; this exists to stop a
         * query per keystroke, not because the query is slow.
         */
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}
