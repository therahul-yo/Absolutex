package com.absolutex.feature.library

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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
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
        placeholder = { Text(stringResource(R.string.library_search_hint)) },
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
 * Sort and view on one line: sort as filter chips (tapping the active one flips its direction,
 * and its arrow says which way it runs), view as an icon toggle at the end.
 */
@Composable
internal fun BrowseControls(state: LibraryUiState, actions: LibraryActions, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.Gap),
        ) {
            SortKey.entries.forEach { key ->
                val active = state.sort.key == key
                FilterChip(
                    selected = active,
                    onClick = {
                        haptics.select()
                        actions.onSortChange(key)
                    },
                    label = { Text(key.label()) },
                    trailingIcon = if (active) {
                        { SortArrow(state.sort.ascending) }
                    } else {
                        null
                    },
                )
            }
        }
        LayoutToggle(state.layout) {
            haptics.select()
            actions.onLayoutChange(it)
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
        modifier = Modifier.size(FilterChipDefaults.IconSize).rotate(turn),
    )
}

/** List, details or grid, as a connected icon toggle. */
@Composable
private fun LayoutToggle(layout: BrowseLayout, onChange: (BrowseLayout) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        BrowseLayout.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = layout == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, BrowseLayout.entries.size),
                icon = {},
                label = { Icon(option.icon(), contentDescription = option.label()) },
            )
        }
    }
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
private const val HALF_TURN = 180f
