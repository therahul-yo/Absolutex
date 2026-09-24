package com.absolutex.feature.library

import androidx.compose.ui.layout.layout
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.absolutex.core.ui.A11y
import com.absolutex.core.ui.rememberHaptics

private val ThumbWidth = 64.dp
private val SmallThumbWidth = 36.dp

/** The hero card is wider than a cover is tall-proportioned, so the art is cropped a little. */
private const val HERO_ASPECT = 0.8f
private const val SCRIM_ALPHA = 0.55f
private const val HERO_SCRIM_ALPHA = 0.85f

/** What a row needs to render and report itself, bundled so parameter lists stay short. */
internal data class RowContext(
    val selectionActive: Boolean,
    val onOpen: (LibraryBookUi) -> Unit,
    val onToggleSelection: (String) -> Unit,
)

/**
 * The whole browse surface: one lazy container, whatever the layout.
 *
 * Recent leads with its newest book as a large hero card — the "continue reading" slot — and the
 * rest follow in the chosen layout.
 */
@Composable
internal fun LibraryPane(
    state: LibraryUiState,
    context: RowContext,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    onSeeAll: () -> Unit = {},
) {
    val books = state.visibleBooks
    val hero = books.firstOrNull()?.takeIf { state.section == HomeSection.RECENT }
    val rest = if (hero == null) books else books.drop(1)
    // Continue reading: in-progress comics on the Comics tab, newest first.
    // Not while searching: the strip is a shortcut home, not a search result.
    val continueReading = if (state.section == HomeSection.COMICS && state.query.isBlank()) {
        state.allBooks.filter { !it.isBook && it.readState == ReadState.IN_PROGRESS }
            .sortedByDescending { it.lastReadAt ?: 0L }
            .take(CONTINUE_LIMIT)
    } else {
        emptyList()
    }
    val padding = PaddingValues(horizontal = Space.Edge, vertical = Space.Gap)
    if (state.layout == BrowseLayout.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(state.grid.columnsFor(landscape)),
            modifier = modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(Space.Row),
            verticalArrangement = Arrangement.spacedBy(Space.Row),
        ) {
            gridContent(state, hero, rest, context, continueReading, onSeeAll)
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(Space.Gap),
        ) {
            listContent(state, hero, rest, context, continueReading, onSeeAll)
        }
    }
}

private fun LazyGridScope.gridContent(
    state: LibraryUiState,
    hero: LibraryBookUi?,
    rest: List<LibraryBookUi>,
    context: RowContext,
    continueReading: List<LibraryBookUi>,
    onSeeAll: () -> Unit,
) {
    if (continueReading.isNotEmpty()) {
        item(key = "continue_reading", span = { GridItemSpan(maxLineSpan) }) {
            ContinueReadingStrip(continueReading, context, onSeeAll, Modifier)
        }
    }
    hero?.let { book ->
        item(key = "hero:${book.path}", span = { GridItemSpan(maxLineSpan) }) {
            HeroCard(book, book.path in state.selected, context, Modifier)
        }
    }
    items(rest, key = { it.path }) { book ->
        CoverCard(book, book.path in state.selected, context, Modifier)
    }
}

private fun LazyListScope.listContent(
    state: LibraryUiState,
    hero: LibraryBookUi?,
    rest: List<LibraryBookUi>,
    context: RowContext,
    continueReading: List<LibraryBookUi>,
    onSeeAll: () -> Unit,
) {
    if (continueReading.isNotEmpty()) {
        item(key = "continue_reading") {
            ContinueReadingStrip(continueReading, context, onSeeAll, Modifier.fillMaxWidth())
        }
    }
    hero?.let { book ->
        item(key = "hero:${book.path}") {
            HeroCard(book, book.path in state.selected, context, Modifier)
        }
    }
    items(rest, key = { it.path }) { book -> BookRow(state, book, context, Modifier) }
}

/**
 * The grid card: the cover large and rounded, then title, size, a format pill and the date — the
 * facts a shelf of files needs, in the order the eye wants them.
 */
@Composable
internal fun CoverCard(book: LibraryBookUi, isSelected: Boolean, context: RowContext, modifier: Modifier) {
    SelectableSurface(book, isSelected, context, modifier) {
        Column {
            Box {
                BookCover(book, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                if (!book.isBook && book.readState == ReadState.UNREAD) NewBadge(Modifier.align(Alignment.TopStart))
                SelectedMark(isSelected)
            }
            book.progressFraction?.let { ReadingProgress(it, Modifier.padding(horizontal = Space.Gap)) }
            Column(
                Modifier.padding(horizontal = Space.Row, vertical = Space.Gap),
                verticalArrangement = Arrangement.spacedBy(Space.Tight),
            ) {
                Text(
                    book.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // A comic card says how far in you are; a document, how big it is.
                Text(
                    (if (book.isBook) null else book.progressLabel()) ?: book.sizeLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FormatAndDate(book, Modifier.padding(top = Space.Tight))
            }
        }
    }
}

/** Continue reading: the newest book full width, its facts over a scrim at the foot of the art. */
@Composable
private fun HeroCard(book: LibraryBookUi, isSelected: Boolean, context: RowContext, modifier: Modifier) {
    SelectableSurface(book, isSelected, context, modifier.padding(bottom = Space.Gap)) {
        Box {
            BookCover(book, Modifier.fillMaxWidth(), aspect = HERO_ASPECT, shape = MaterialTheme.shapes.large)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(HERO_SCRIM_ALPHA))))
                    .padding(Space.Edge),
                verticalArrangement = Arrangement.spacedBy(Space.Tight),
            ) {
                Text(
                    book.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(book.positionLabel(), style = MaterialTheme.typography.labelLarge, color = Color.White)
                book.progressFraction?.let { ReadingProgress(it) }
                FormatAndDate(book, Modifier.padding(top = Space.Tight))
            }
            SelectedMark(isSelected)
        }
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
            val detailed = state.layout == BrowseLayout.DETAILED_LIST
            Box(Modifier.width(if (detailed) ThumbWidth else SmallThumbWidth)) {
                BookCover(book, Modifier.fillMaxWidth())
                SelectedMark(selected)
            }
            if (detailed) {
                DetailedBookContent(book, Modifier.weight(1f))
            } else {
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
            stringResource(R.string.library_book_facts, book.pagesLabel(), book.sizeLabel()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormatAndDate(book)
        book.progressFraction?.let { ReadingProgress(it) }
    }
}

@Composable
private fun ReadingProgress(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction },
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth().padding(top = Space.Tight),
    )
}

/**
 * A check over the art of a selected book; the card's label already says so aloud. Composed only
 * while selected — an AnimatedVisibility on every card cost its transition setup on every card of
 * every screen, which is most of what made the grid slow to come back after closing a book.
 */
@Composable
private fun BoxScope.SelectedMark(selected: Boolean) {
    if (!selected) return
    Box(
        Modifier
            .matchParentSize()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Tap and long-press behaviour shared by every card and row.
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
    val container = with(MaterialTheme.colorScheme) { if (isSelected) surfaceContainerHighest else surfaceContainerLow }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = {
                    if (context.selectionActive) context.onToggleSelection(book.path) else context.onOpen(book)
                },
                onLongClick = {
                    haptics.confirm()
                    context.onToggleSelection(book.path)
                },
            )
            // One spoken label per card: unlabelled, TalkBack reads six text nodes one swipe at a
            // time and never announces the selected state at all.
            .clearAndSetSemantics { contentDescription = label },
        content = content,
    )
}

/** A small solid tag on a comic that has never been opened. */
@Composable
private fun NewBadge(modifier: Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier.padding(Space.Gap),
    ) {
        Text(
            stringResource(R.string.library_badge_new).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
