package com.absolutex.feature.library

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.scan.SortKey

/**
 * The library screen.
 *
 * The one public entry point of this module — navigation is wired by :app, which is why this
 * takes plain callbacks rather than a NavController.
 *
 * @param onOpenBook handed the absolute path of the book to open.
 * @param onAddLocation invoked from the empty state a fresh install sees; the storage-location
 *   picker lives outside this module.
 */
@Composable
fun LibraryRoute(
    onOpenBook: (String) -> Unit,
    onAddLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LibraryViewModel = hiltViewModel()
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { viewModel.actions() }
    LibraryScreen(
        state = state,
        actions = actions,
        onOpenBook = onOpenBook,
        onAddLocation = onAddLocation,
        modifier = modifier,
    )
}

/** Every intent the screen can raise, bundled so no composable takes a dozen lambdas. */
internal data class LibraryActions(
    val onQueryChange: (String) -> Unit,
    val onSectionChange: (HomeSection) -> Unit,
    val onLayoutChange: (BrowseLayout) -> Unit,
    val onSortChange: (SortKey) -> Unit,
    val onGridColumns: (Boolean, Int) -> Unit,
    val onToggleSelection: (String) -> Unit,
    val onClearSelection: () -> Unit,
    val onSelectAll: () -> Unit,
    val onBatch: (BatchAction) -> Unit,
    val onMessageShown: () -> Unit,
)

internal fun LibraryViewModel.actions() = LibraryActions(
    onQueryChange = ::onQueryChange,
    onSectionChange = ::onSectionChange,
    onLayoutChange = ::onLayoutChange,
    onSortChange = ::onSortChange,
    onGridColumns = ::onGridColumnsChange,
    onToggleSelection = ::onToggleSelection,
    onClearSelection = ::onClearSelection,
    onSelectAll = ::onSelectAllVisible,
    onBatch = ::onBatch,
    onMessageShown = ::onMessageShown,
)

@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    onOpenBook: (String) -> Unit,
    onAddLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // §7: one declaration adapts to bottom bar, rail or drawer across phone, tablet and foldable.
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            HomeSection.entries.forEach { section ->
                item(
                    selected = section == state.section,
                    onClick = { actions.onSectionChange(section) },
                    icon = { Text(section.glyph()) },
                    label = { Text(section.label()) },
                )
            }
        },
        modifier = modifier,
    ) {
        LibraryBody(state, actions, onOpenBook, onAddLocation)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryBody(
    state: LibraryUiState,
    actions: LibraryActions,
    onOpenBook: (String) -> Unit,
    onAddLocation: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    // Resolved here and not inside the effect: the words are a string resource, and the effect's
    // block is not @Composable. Keyed on the notice itself rather than its text, so two identical
    // messages in a row both show.
    val notice = state.message
    val noticeText = notice?.text()
    LaunchedEffect(notice) {
        if (notice == null || noticeText == null) return@LaunchedEffect
        snackbar.showSnackbar(noticeText)
        actions.onMessageShown()
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.library_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        // Scaffold consumes the system bars for us; the app draws edge to edge (§7).
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.selectionActive) SelectionBar(state, actions)
            LibraryControls(state, actions)
            LibraryContent(state, actions, onOpenBook, onAddLocation)
        }
    }
}

@Composable
private fun LibraryControls(state: LibraryUiState, actions: LibraryActions) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(
        modifier = Modifier.padding(horizontal = Space.Edge),
        verticalArrangement = Arrangement.spacedBy(Space.Tight),
    ) {
        LibrarySearchField(state.query, actions.onQueryChange)
        SortControl(state.sort, actions.onSortChange)
        LayoutControl(state.layout, actions.onLayoutChange)
        if (state.layout == BrowseLayout.GRID) {
            GridColumnControl(state.grid, landscape, actions.onGridColumns)
        }
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    actions: LibraryActions,
    onOpenBook: (String) -> Unit,
    onAddLocation: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val error = state.error
    if (error != null) {
        // A database failure replaces the whole surface, including the empty state: "nothing here
        // yet" over a library that could not be read is a different claim, and a false one.
        LibraryErrorState(error)
        return
    }
    if (state.emptyReason != LibraryEmptyReason.NONE) {
        LibraryEmptyState(state.emptyReason, state.section, state.query, onAddLocation)
        return
    }
    // Built rather than remembered: it is three fields, and a remember keyed on only some of
    // the lambdas it captures is how a stale callback survives a recomposition.
    val context = RowContext(
        selectionActive = state.selectionActive,
        onOpen = { book -> onOpenBook(book.path) },
        onToggleSelection = actions.onToggleSelection,
    )
    val shelves = when (state.section) {
        HomeSection.SERIES -> state.seriesShelves
        HomeSection.FOLDERS -> state.folderShelves
        else -> null
    }
    Box(Modifier.fillMaxSize()) {
        LibraryPane(state, shelves, context, landscape)
    }
}
