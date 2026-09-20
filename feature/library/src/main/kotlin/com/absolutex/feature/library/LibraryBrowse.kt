package com.absolutex.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.absolutex.core.ui.A11y

/** Cover aspect ratio. Comic pages are taller than wide; 2:3 is the common trim. */
private const val COVER_ASPECT = 2f / 3f

private val ThumbWidth = 56.dp

/** What a row needs to render and report itself, bundled so parameter lists stay short. */
internal data class RowContext(
    val selectionActive: Boolean,
    val onOpen: (LibraryBookUi) -> Unit,
    val onToggleSelection: (String) -> Unit,
)

/**
 * The whole browse surface: one lazy container, whatever the layout and whether or not the
 * section is grouped into shelves.
 *
 * Deliberately *one* — an earlier shape nested a per-shelf list inside an outer list, which is
 * the classic Compose crash: a vertically scrollable measured with infinite height. Shelf headers
 * are items in the same container instead, spanning the full width in the grid.
 *
 * @param shelves null for a flat section; a grouped section passes its shelves.
 */
@Composable
internal fun LibraryPane(
    state: LibraryUiState,
    shelves: List<Shelf>?,
    context: RowContext,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    if (state.layout == BrowseLayout.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(state.grid.columnsFor(landscape)),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(Space.Tight),
            horizontalArrangement = Arrangement.spacedBy(Space.Tight),
            verticalArrangement = Arrangement.spacedBy(Space.Tight),
        ) {
            gridContent(state, shelves, context)
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            listContent(state, shelves, context)
        }
    }
}

private fun LazyGridScope.gridContent(
    state: LibraryUiState,
    shelves: List<Shelf>?,
    context: RowContext,
) {
    if (shelves == null) {
        items(state.visibleBooks, key = { it.path }) { book ->
            GridCell(book, book.path in state.selected, context)
        }
        return
    }
    shelves.forEach { shelf ->
        item(key = "shelf:${shelf.id}", span = { GridItemSpan(maxLineSpan) }) { ShelfHeader(shelf) }
        items(shelf.books, key = { it.path }) { book ->
            GridCell(book, book.path in state.selected, context)
        }
    }
}

private fun LazyListScope.listContent(
    state: LibraryUiState,
    shelves: List<Shelf>?,
    context: RowContext,
) {
    if (shelves == null) {
        items(state.visibleBooks, key = { it.path }) { book -> BookRow(state, book, context) }
        return
    }
    shelves.forEach { shelf ->
        item(key = "shelf:${shelf.id}") { ShelfHeader(shelf) }
        items(shelf.books, key = { it.path }) { book -> BookRow(state, book, context) }
    }
}

@Composable
private fun BookRow(state: LibraryUiState, book: LibraryBookUi, context: RowContext) {
    SelectableRow(book, book.path in state.selected, context) {
        if (state.layout == BrowseLayout.DETAILED_LIST) {
            DetailedBookContent(book)
        } else {
            Text(
                book.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Thumbnail, title, page count, current page, size and original filename (§5.1). */
@Composable
private fun DetailedBookContent(book: LibraryBookUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Row)) {
        CoverPlaceholder(Modifier.width(ThumbWidth))
        Column(verticalArrangement = Arrangement.spacedBy(Space.Tight)) {
            Text(
                book.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(book.pagesLabel(), style = MaterialTheme.typography.bodySmall)
            Text(book.positionLabel(), style = MaterialTheme.typography.bodySmall)
            Text(formatSize(book.sizeBytes), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.library_book_filename, book.originalFilename),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            book.progressFraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun GridCell(book: LibraryBookUi, isSelected: Boolean, context: RowContext) {
    SelectableSurface(book, isSelected, context, Modifier) {
        Column(Modifier.padding(Space.Tight)) {
            CoverPlaceholder(Modifier.fillMaxWidth())
            Text(
                book.displayName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            book.progressFraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SelectableRow(
    book: LibraryBookUi,
    isSelected: Boolean,
    context: RowContext,
    content: @Composable () -> Unit,
) {
    SelectableSurface(book, isSelected, context, Modifier.heightIn(min = A11y.MinTouchTarget)) {
        Row(
            modifier = Modifier.padding(horizontal = Space.Edge, vertical = Space.Row),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Row),
        ) {
            if (context.selectionActive) {
                // Decorative: the surface already announces selected state in one spoken label.
                Checkbox(checked = isSelected, onCheckedChange = null)
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/**
 * Tap and long-press behaviour shared by rows and grid cells.
 *
 * Long-press starts selection; once it has started a plain tap toggles instead of opening. That
 * is the convention every file manager uses, and the only way to extend a selection without
 * hunting for a checkbox.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableSurface(
    book: LibraryBookUi,
    isSelected: Boolean,
    context: RowContext,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val label = book.accessibilityLabel(isSelected, context.selectionActive)
    Surface(
        tonalElevation = if (isSelected) Space.Tight else Space.Zero,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (context.selectionActive) context.onToggleSelection(book.path) else context.onOpen(book)
                },
                onLongClick = { context.onToggleSelection(book.path) },
            )
            // One spoken label per row: unlabelled, TalkBack reads six text nodes one swipe at a
            // time and never announces the selected state at all.
            .clearAndSetSemantics { contentDescription = label },
        content = content,
    )
}

/** No cover pipeline yet; a labelled placeholder is honest where a blank box is not. */
@Composable
private fun CoverPlaceholder(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.library_cover_placeholder)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .aspectRatio(COVER_ASPECT)
            .clearAndSetSemantics { contentDescription = label },
    ) { Box(Modifier) }
}
