package com.absolutex.feature.library

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import com.absolutex.core.ui.Motion

/**
 * Selection mode's top bar (§5.1), standing in for the normal one rather than stacking a second
 * bar under it: close on the left, the live count, and the actions as icons on the right.
 *
 * The count animates as it changes so a tap visibly lands. Back leaves selection mode, the way
 * every Android app with a contextual bar behaves. An action with nothing behind it is absent
 * rather than disabled: a disabled button never receives a tap, so it can never explain itself.
 * See [LibraryCapabilities].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(state: LibraryUiState, actions: LibraryActions) {
    BackHandler(onBack = actions.onClearSelection)
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = actions.onClearSelection) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.library_selection_clear))
            }
        },
        title = {
            AnimatedContent(state.selectedCount, transitionSpec = { countChange() }, label = "count") { count ->
                Text(pluralStringResource(R.plurals.library_selected_count, count, count))
            }
        },
        actions = {
            IconButton(onClick = actions.onSelectAll) {
                Icon(
                    Icons.Outlined.SelectAll,
                    contentDescription = stringResource(R.string.library_selection_select_all),
                )
            }
            if (state.capabilities.canFavorite) {
                IconButton(onClick = { actions.onBatch(BatchAction.FAVORITE) }) {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = stringResource(R.string.library_favorite))
                }
            }
            if (state.capabilities.canMarkRead || state.capabilities.canDelete) SelectionOverflow(state, actions)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

/** The count slides up when it grows and down when it shrinks, like an odometer. */
private fun AnimatedContentTransitionScope<Int>.countChange(): ContentTransform {
    val up = targetState > initialState
    return (slideInVertically(Motion.enter()) { if (up) it else -it } + fadeIn(Motion.enter())) togetherWith
        (slideOutVertically(Motion.exit()) { if (up) -it else it } + fadeOut(Motion.exit()))
}

@Composable
private fun SelectionOverflow(state: LibraryUiState, actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.library_selection_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.capabilities.canMarkRead) {
                BatchMenuItem(BatchAction.MARK_READ, R.string.library_mark_read, actions) { open = false }
                BatchMenuItem(BatchAction.MARK_UNREAD, R.string.library_mark_unread, actions) { open = false }
            }
            if (state.capabilities.canDelete) {
                BatchMenuItem(BatchAction.DELETE, R.string.library_delete, actions) { open = false }
            }
        }
    }
}

@Composable
private fun BatchMenuItem(
    action: BatchAction,
    @StringRes label: Int,
    actions: LibraryActions,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            actions.onBatch(action)
            onDismiss()
        },
    )
}

