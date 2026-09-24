package com.absolutex.feature.library

import com.absolutex.core.ui.rememberHaptics
import com.absolutex.core.ui.Motion
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
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

private val ThumbWidth = 64.dp
private val SmallThumbWidth = 36.dp

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
            contentPadding = PaddingValues(horizontal = Space.Edge, vertical = Space.Gap),
            horizontalArrangement = Arrangement.spacedBy(Space.Row),
            verticalArrangement = Arrangement.spacedBy(Space.Row),
        ) {
            gridContent(state, shelves, context)
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Space.Edge, vertical = Space.Gap),
            verticalArrangement = Arrangement.spacedBy(Space.Gap),
        ) {
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
            GridCell(book, book.path in state.selected, context, Modifier.animateItem())
        }
        return
    }
    shelves.forEach { shelf ->
        item(key = "shelf:${shelf.id}", span = { GridItemSpan(maxLineSpan) }) { ShelfHeader(shelf) }
        items(shelf.books, key = { it.path }) { book ->
            GridCell(book, book.path in state.selected, context, Modifier.animateItem())
        }
    }
}

private fun LazyListScope.listContent(
    state: LibraryUiState,
    shelves: List<Shelf>?,
    context: RowContext,
) {
    if (shelves == null) {
        items(state.visibleBooks, key = { it.path }) { book -> BookRow(state, book, context, Modifier.animateItem()) }
        return
    }
    shelves.forEach { shelf ->
        item(key = "shelf:${shelf.id}") { ShelfHeader(shelf) }
        items(shelf.books, key = { it.path }) { book -> BookRow(state, book, context, Modifier.animateItem()) }
    }
}

@Composable
private fun BookRow(state: LibraryUiState, book: LibraryBookUi, context: RowContext, modifier: Modifier) {
    val selected = book.path in state.selected
    SelectableSurface(book, selected, context, modifier.heightIn(min = A11y.MinTouchTarget)) {
        Row(
            modifier = Modifier.padding(Space.Row),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Row),
        ) {
            if (state.layout == BrowseLayout.DETAILED_LIST) {
                SelectableCover(book, selected, Modifier.width(ThumbWidth))
                DetailedBookContent(book, Modifier.weight(1f))
            } else {
                SelectableCover(book, selected, Modifier.width(SmallThumbWidth))
                Text(
                    book.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Title, facts and progress (§5.1). The SAF document id is not a filename, so it is not shown. */
@Composable
private fun DetailedBookContent(book: LibraryBookUi, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.Tight)) {
        Text(
            book.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${book.pagesLabel()} · ${book.sizeLabel()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            book.positionLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        book.progressFraction?.let { ReadingProgress(it) }
    }
}

@Composable
private fun ReadingProgress(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction },
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(top = Space.Tight),
    )
}

/** A cover that shows a check over itself when its book is selected. */
@Composable
private fun SelectableCover(book: LibraryBookUi, selected: Boolean, modifier: Modifier) {
    Box(modifier.clip(MaterialTheme.shapes.extraSmall)) {
        BookCover(book, Modifier.fillMaxWidth())
        AnimatedVisibility(
            selected,
            enter = fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = CHECK_START_SCALE),
            exit = fadeOut(Motion.exit()),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative: the surface already announces selected state in one spoken label.
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun GridCell(book: LibraryBookUi, isSelected: Boolean, context: RowContext, modifier: Modifier) {
    SelectableSurface(book, isSelected, context, modifier) {
        Column(Modifier.padding(Space.Gap), verticalArrangement = Arrangement.spacedBy(Space.Gap)) {
            SelectableCover(book, isSelected, Modifier.fillMaxWidth())
            Text(
                book.displayName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            book.progressFraction?.let { ReadingProgress(it) }
        }
    }
}

private const val SCRIM_ALPHA = 0.55f
private const val CHECK_START_SCALE = 0.6f

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
    val haptics = rememberHaptics()
    val container by animateColorAsState(
        with(MaterialTheme.colorScheme) { if (isSelected) surfaceContainerHighest else surfaceContainerLow },
        Motion.enter(),
        label = "selected",
    )
    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = {
                    if (context.selectionActive) context.onToggleSelection(book.path) else context.onOpen(book)
                },
                onLongClick = {
                    haptics.confirm()
                    context.onToggleSelection(book.path)
                },
            )
            // One spoken label per row: unlabelled, TalkBack reads six text nodes one swipe at a
            // time and never announces the selected state at all.
            .clearAndSetSemantics { contentDescription = label },
        content = content,
    )
}

