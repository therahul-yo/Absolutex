package com.absolutex.feature.library

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
import androidx.compose.material3.OutlinedTextField
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

@Composable
internal fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text(stringResource(R.string.library_search_hint)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                // Resolved outside the semantics lambda: stringResource is @Composable,
                // and the lambda passed to semantics is not.
                val clearLabel = stringResource(R.string.library_search_clear)
                TextButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .heightIn(min = Space.MinTouchTarget)
                        .semantics { contentDescription = clearLabel },
                ) { Text("✕") }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SortControl(sort: SortSpec, onSortChange: (SortKey) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val direction = if (sort.ascending) {
        stringResource(R.string.library_sort_ascending)
    } else {
        stringResource(R.string.library_sort_descending)
    }
    val label = "${stringResource(R.string.library_sort)}: ${sort.key.label()}, $direction"
    Row(modifier = modifier) {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = Space.MinTouchTarget),
        ) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortKey.entries.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key.label()) },
                    onClick = {
                        onSortChange(key)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SortKey.label(): String = when (this) {
    SortKey.NAME -> stringResource(R.string.library_sort_name)
    SortKey.SIZE -> stringResource(R.string.library_sort_size)
    SortKey.DATE -> stringResource(R.string.library_sort_date)
}

@Composable
internal fun LayoutControl(
    layout: BrowseLayout,
    onLayoutChange: (BrowseLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier = modifier) {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = Space.MinTouchTarget),
        ) { Text("${stringResource(R.string.library_layout)}: ${layout.label()}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BrowseLayout.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        onLayoutChange(option)
                        open = false
                    },
                )
            }
        }
    }
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
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onColumns(landscape, columns - 1) },
            enabled = columns > GridSpec.MIN_COLUMNS,
            modifier = Modifier.heightIn(min = Space.MinTouchTarget),
        ) { Text(stringResource(R.string.library_grid_fewer)) }
        Text(stringResource(R.string.library_grid_columns, columns))
        TextButton(
            onClick = { onColumns(landscape, columns + 1) },
            enabled = columns < GridSpec.MAX_COLUMNS,
            modifier = Modifier.heightIn(min = Space.MinTouchTarget),
        ) { Text(stringResource(R.string.library_grid_more)) }
    }
}

/**
 * Batch action bar (§5.1).
 *
 * The count is the only thing that gives way and every action keeps its own width, because the
 * shape this replaced — a count plus six `TextButton`s in one non-scrolling `Row` — squeezed the
 * last actions to nothing on a 360 dp phone, which is a target nobody can hit (§7). Secondary
 * actions moved into an overflow menu.
 *
 * An action with nothing behind it is absent rather than disabled: a disabled button never
 * receives a tap, so it can never explain itself. See [LibraryCapabilities].
 */
@Composable
internal fun SelectionBar(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = Space.Edge, vertical = Space.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.library_selected_count,
                    state.selectedCount,
                    state.selectedCount,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Weighted on purpose: measured last, the count would push the actions off the
                // edge, which is the bug. Measured with a share, it ellipsizes instead.
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            // Short on screen, full sentence to TalkBack: the spoken label costs no width.
            val selectAllLabel = stringResource(R.string.library_selection_select_all)
            TextButton(
                onClick = actions.onSelectAll,
                modifier = Modifier
                    .heightIn(min = Space.MinTouchTarget)
                    .semantics { contentDescription = selectAllLabel },
            ) {
                Text(stringResource(R.string.library_selection_select_all_short))
            }
            if (state.capabilities.hasAny) SelectionActions(state, actions)
            // Resolved outside the semantics lambda: stringResource is @Composable and the
            // lambda passed to semantics is not.
            val clearLabel = stringResource(R.string.library_selection_clear)
            TextButton(
                onClick = actions.onClearSelection,
                modifier = Modifier
                    .heightIn(min = Space.MinTouchTarget)
                    .semantics { contentDescription = clearLabel },
            ) { Text("✕") }
        }
    }
}

@Composable
private fun SelectionActions(state: LibraryUiState, actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.library_selection_more)
    Box {
        TextButton(
            onClick = { open = true },
            // §7: the glyph is decoration; this is what TalkBack announces.
            modifier = Modifier
                .heightIn(min = Space.MinTouchTarget)
                .semantics { contentDescription = label },
        ) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.capabilities.canMarkRead) {
                BatchMenuItem(BatchAction.MARK_READ, R.string.library_mark_read, actions) { open = false }
                BatchMenuItem(BatchAction.MARK_UNREAD, R.string.library_mark_unread, actions) { open = false }
            }
            if (state.capabilities.canFavorite) {
                BatchMenuItem(BatchAction.FAVORITE, R.string.library_favorite, actions) { open = false }
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

