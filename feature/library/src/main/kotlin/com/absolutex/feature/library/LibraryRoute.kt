package com.absolutex.feature.library

import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import com.absolutex.core.ui.rememberHaptics
import com.absolutex.core.ui.Motion
import com.absolutex.core.ui.A11y
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material.icons.Icons
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
 * @param onOpenSettings the library's way in to the settings screen. Until this existed the only
 *   navigation to it was from the reader chrome, so a fresh install with an empty library could
 *   not reach settings at all — and therefore could not add a storage location from there, change
 *   a preference, or open the remote servers list.
 */
@Composable
fun LibraryRoute(
    onOpenBook: (String) -> Unit,
    onAddLocation: () -> Unit,
    onOpenSettings: () -> Unit = {},
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
        onOpenSettings = onOpenSettings,
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
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    // §7: one declaration adapts to bottom bar, rail or drawer across phone, tablet and foldable.
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            HomeSection.entries.forEach { section ->
                val selected = section == state.section
                item(
                    selected = selected,
                    onClick = {
                        if (!selected) haptics.select()
                        actions.onSectionChange(section)
                    },
                    // The label names the tab for TalkBack, so the icon is decoration.
                    icon = { Icon(section.icon(selected), contentDescription = null) },
                    label = { Text(section.label()) },
                )
            }
        },
        modifier = modifier,
    ) {
        LibraryBody(state, actions, onOpenBook, onAddLocation, onOpenSettings)
    }
}

@Composable
private fun LibraryBody(
    state: LibraryUiState,
    actions: LibraryActions,
    onOpenBook: (String) -> Unit,
    onAddLocation: () -> Unit,
    onOpenSettings: () -> Unit,
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
        topBar = {
            // Selection swaps the bar in place — no second bar pushing the list down.
            AnimatedContent(
                state.selectionActive,
                transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                label = "bar",
            ) { selecting ->
                if (selecting) SelectionTopBar(state, actions) else LibraryTopBar(state.section, onOpenSettings)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // Scaffold consumes the system bars for us; the app draws edge to edge (§7).
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LibraryControls(state, actions)
            LibraryContent(state, actions, onOpenBook, onAddLocation)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(section: HomeSection, onOpenSettings: () -> Unit) {
    TopAppBar(
        title = {
            // The section name is the title; it cross-fades rather than jumping.
            Crossfade(section, animationSpec = Motion.enter(), label = "title") { shown ->
                Text(shown.label(), style = MaterialTheme.typography.headlineMedium)
            }
        },
        actions = {
            // An icon-only control, so the description is the only thing a screen reader has to
            // go on. Tonal and full-size so it reads as a button, not a stray glyph.
            FilledTonalIconButton(
                onClick = onOpenSettings,
                modifier = Modifier.padding(end = Space.Gap).size(A11y.MinTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.library_open_settings),
                )
            }
        },
    )
}

@Composable
private fun LibraryControls(state: LibraryUiState, actions: LibraryActions) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(
        modifier = Modifier.padding(horizontal = Space.Edge).padding(bottom = Space.Gap),
        verticalArrangement = Arrangement.spacedBy(Space.Gap),
    ) {
        LibrarySearchField(state.query, actions.onQueryChange)
        BrowseControls(state, actions)
        AnimatedVisibility(
            state.layout == BrowseLayout.GRID,
            enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
        ) {
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
    // Material fade-through between tabs: the old tab fades out fast, the new one fades in just
    // after, and each renders its own state — the outgoing grid never flashes the new tab's books.
    // Each tab also starts at its own top rather than inheriting the last tab's scroll offset.
    AnimatedContent(
        targetState = state,
        contentKey = { it.section },
        transitionSpec = {
            fadeIn(tween(Motion.MEDIUM_MS, delayMillis = Motion.SHORT_MS / 2)) togetherWith
                fadeOut(tween(Motion.SHORT_MS))
        },
        modifier = Modifier.fillMaxSize(),
        label = "tab",
    ) { shown ->
        LibraryPane(shown, context, landscape)
    }
}
