package com.absolutex.feature.library

import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.activity.compose.BackHandler
import com.absolutex.core.ui.rememberHaptics
import com.absolutex.core.ui.Motion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.absolutex.core.scan.SortKey
import com.absolutex.core.ui.A11y

/** The search field: a filled, fully rounded pill, the Material 3 search-bar shape. */
@Composable
internal fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(stringResource(R.string.library_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.library_search_clear))
                }
            }
        },
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = SearchHeight),
    )
}

/**
 * Sort and view as two round buttons beside the search field, each opening a short menu. They
 * were two full rows (chips, a segmented toggle and a column stepper) — a quarter of the screen
 * above the covers, with Size and Date squeezed out of sight.
 */
@Composable
internal fun BrowseMenus(state: LibraryUiState, actions: LibraryActions, landscape: Boolean) {
    SortMenu(state.sort, actions.onSortChange)
    ViewMenu(state.layout, state.grid, landscape, actions)
}

/** Name, size or date; the active one is checked, and choosing it again flips its direction. */
@Composable
private fun SortMenu(sort: SortSpec, onSort: (SortKey) -> Unit) {
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { open = true }, modifier = Modifier.size(A11y.MinTouchTarget)) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.library_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortKey.entries.forEach { key ->
                val active = sort.key == key
                DropdownMenuItem(
                    text = { Text(key.label()) },
                    leadingIcon = { if (active) Icon(Icons.Outlined.Check, contentDescription = null) },
                    trailingIcon = { if (active) SortArrow(sort.ascending) },
                    onClick = {
                        haptics.select()
                        onSort(key)
                        open = false
                    },
                )
            }
        }
    }
}

/** List, details or grid — and, for the grid, how many columns — behind the current layout's icon. */
@Composable
private fun ViewMenu(layout: BrowseLayout, grid: GridSpec, landscape: Boolean, actions: LibraryActions) {
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { open = true }, modifier = Modifier.size(A11y.MinTouchTarget)) {
            Icon(layout.icon(), contentDescription = stringResource(R.string.library_layout))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BrowseLayout.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    leadingIcon = { Icon(option.icon(), contentDescription = null) },
                    trailingIcon = { if (option == layout) Icon(Icons.Outlined.Check, contentDescription = null) },
                    onClick = {
                        haptics.select()
                        actions.onLayoutChange(option)
                        if (option != BrowseLayout.GRID) open = false
                    },
                )
            }
            if (layout == BrowseLayout.GRID) {
                GridColumnControl(grid, landscape, actions.onGridColumns, Modifier.padding(horizontal = Space.Row))
            }
        }
    }
}

/** The direction arrow turns rather than swapping, so the flip is visible. */
@Composable
private fun SortArrow(ascending: Boolean) {
    val turn by animateFloatAsState(if (ascending) 0f else HALF_TURN, Motion.enter(), label = "sort")
    val direction = if (ascending) {
        stringResource(R.string.library_sort_ascending)
    } else {
        stringResource(R.string.library_sort_descending)
    }
    Icon(
        Icons.Outlined.ArrowUpward,
        contentDescription = direction,
        modifier = Modifier.size(SortArrowSize).rotate(turn),
    )
}


private fun BrowseLayout.icon(): ImageVector = when (this) {
    BrowseLayout.SIMPLE_LIST -> Icons.AutoMirrored.Outlined.ViewList
    BrowseLayout.DETAILED_LIST -> Icons.Outlined.ViewAgenda
    BrowseLayout.GRID -> Icons.Outlined.GridView
}

@Composable
private fun SortKey.label(): String = when (this) {
    SortKey.NAME -> stringResource(R.string.library_sort_name)
    SortKey.SIZE -> stringResource(R.string.library_sort_size)
    SortKey.DATE -> stringResource(R.string.library_sort_date)
}

@Composable
private fun BrowseLayout.label(): String = when (this) {
    BrowseLayout.SIMPLE_LIST -> stringResource(R.string.library_layout_simple)
    BrowseLayout.DETAILED_LIST -> stringResource(R.string.library_layout_detailed)
    BrowseLayout.GRID -> stringResource(R.string.library_layout_grid)
}

/** Column stepper, shown only for the grid and only for the orientation on screen (§5.1). */
@Composable
internal fun GridColumnControl(
    grid: GridSpec,
    landscape: Boolean,
    onColumns: (Boolean, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = grid.columnsFor(landscape)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Row),
    ) {
        FilledTonalIconButton(
            onClick = { onColumns(landscape, columns - 1) },
            enabled = columns > GridSpec.MIN_COLUMNS,
            modifier = Modifier.size(A11y.MinTouchTarget),
        ) { Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.library_grid_fewer)) }
        Text(stringResource(R.string.library_grid_columns, columns), style = MaterialTheme.typography.titleSmall)
        FilledTonalIconButton(
            onClick = { onColumns(landscape, columns + 1) },
            enabled = columns < GridSpec.MAX_COLUMNS,
            modifier = Modifier.size(A11y.MinTouchTarget),
        ) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.library_grid_more)) }
    }
}

private val SearchHeight = 52.dp
private val SortArrowSize = 18.dp
private const val HALF_TURN = 180f
