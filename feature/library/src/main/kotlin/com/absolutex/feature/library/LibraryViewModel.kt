package com.absolutex.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.scan.SortKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the library screens.
 *
 * Holds no rendering logic and no Room types: it moves [LibraryFeed] output into
 * [LibraryUiState] and applies the user's intents. Everything worth asserting about — ordering,
 * section membership, selection, empty states — lives in the plain-Kotlin state layer next door
 * and is unit-tested there.
 */
@HiltViewModel
internal class LibraryViewModel @Inject constructor(
    private val feed: LibraryFeed,
) : ViewModel() {

    private val _ui = MutableStateFlow(LibraryUiState.Initial.copy(capabilities = feed.capabilities))
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    /** The unfiltered library, kept so clearing the query does not need a round trip. */
    private var everything: List<LibraryBookUi> = emptyList()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            feed.observeBooks()
                .catch { failure -> _ui.update { it.copy(loading = false, error = failure.readable()) } }
                .collect { books ->
                    everything = books
                    // A scan landing mid-search must not yank the user's results out from under
                    // them; the next keystroke (or clearing the box) picks the new data up.
                    if (_ui.value.query.isBlank()) {
                        _ui.update { it.copy(loading = false, allBooks = books, error = null) }
                    }
                }
        }
        viewModelScope.launch {
            val located = runCatching { feed.hasLocations() }.getOrDefault(true)
            _ui.update { it.copy(hasLocations = located) }
        }
    }

    /**
     * Search as you type (§5.1).
     *
     * The query text lands in state immediately so the field never lags a keypress, while the
     * lookup itself is debounced and cancellable — one search per pause, not one per character.
     * Nothing rebuilds an index: the repository query is a scan, and clearing the box replays the
     * list already in hand.
     */
    fun onQueryChange(query: String) {
        _ui.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isBlank()) {
                _ui.update { it.copy(allBooks = everything, loading = false) }
                return@launch
            }
            delay(SEARCH_DEBOUNCE_MS)
            val matches = runCatching { feed.search(query) }
            matches.fold(
                onSuccess = { books -> _ui.update { it.copy(allBooks = books, loading = false, error = null) } },
                onFailure = { failure -> _ui.update { it.copy(loading = false, error = failure.readable()) } },
            )
        }
    }

    fun onSectionChange(section: HomeSection) {
        // Leaving a section with rows ticked would strand a selection the user can no longer see.
        _ui.update { it.clearSelection().copy(section = section) }
    }

    fun onLayoutChange(layout: BrowseLayout) = _ui.update { it.copy(layout = layout) }

    fun onSortChange(key: SortKey) = _ui.update { it.copy(sort = it.sort.select(key)) }

    fun onGridColumnsChange(landscape: Boolean, columns: Int) = _ui.update {
        it.copy(grid = it.grid.withColumns(landscape, columns))
    }

    fun onToggleSelection(path: String) = _ui.update { it.toggleSelection(path) }

    fun onClearSelection() = _ui.update { it.clearSelection() }

    fun onSelectAllVisible() = _ui.update { it.selectAllVisible() }

    fun onMessageShown() = _ui.update { it.copy(message = null) }

    /** One entry point for every batch action, matching the one place they are carried out. */
    fun onBatch(action: BatchAction) = runBatch { targets ->
        when (action) {
            BatchAction.MARK_READ -> feed.setRead(targets, read = true)
            BatchAction.MARK_UNREAD -> feed.setRead(targets, read = false)
            BatchAction.FAVORITE -> feed.setFavorite(targets, favorite = true)
            BatchAction.UNFAVORITE -> feed.setFavorite(targets, favorite = false)
            BatchAction.DELETE -> feed.delete(targets)
        }
    }

    /**
     * Runs a batch action over the selection and reports what actually happened.
     *
     * The selection is cleared only on success: leaving it intact after a refusal means the user
     * can act on the same books again without re-picking them.
     */
    private fun runBatch(action: suspend (Set<String>) -> BatchOutcome) {
        val targets = _ui.value.selected
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val outcome = runCatching { action(targets) }
                .getOrElse { BatchOutcome.Failed(it.readable()) }
            _ui.update { state ->
                when (outcome) {
                    is BatchOutcome.Applied -> state.clearSelection().copy(message = outcome.describe())
                    is BatchOutcome.Unsupported -> state.copy(message = outcome.reason)
                    is BatchOutcome.Failed -> state.copy(message = outcome.reason)
                }
            }
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

private fun Throwable.readable(): String = message?.takeIf { it.isNotBlank() } ?: "Something went wrong."

private fun BatchOutcome.Applied.describe(): String = when {
    count == 0 && skipped > 0 -> "Nothing changed — $skipped need opening first."
    skipped > 0 -> "Updated $count. $skipped need opening first."
    else -> "Updated $count."
}
