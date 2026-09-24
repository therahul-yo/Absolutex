package com.absolutex.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One cell of the Comics grid: a single book, or a series collapsed into a stack. */
internal sealed interface ShelfEntry {
    val key: String

    data class One(val book: LibraryBookUi) : ShelfEntry {
        override val key get() = book.path
    }

    data class Stack(val series: String, val books: List<LibraryBookUi>) : ShelfEntry {
        override val key get() = "series:$series"
    }
}

/**
 * Collapses the issues of one series into a single stack, in the order the grid already had them
 * (a stack sits where its first issue did). A series of one stays a plain book: a stack of one is
 * a tap that opens a screen showing the same single cover.
 */
internal fun List<LibraryBookUi>.stacked(): List<ShelfEntry> {
    val bySeries = filter { it.series != null }.groupBy { it.series!! }
    val placed = HashSet<String>()
    return mapNotNull { book ->
        val series = book.series
        val run = series?.let { bySeries[it] }
        when {
            run == null || run.size < 2 -> ShelfEntry.One(book)
            placed.add(series) -> ShelfEntry.Stack(series, run)
            else -> null
        }
    }
}

/**
 * A series as a stack of covers: the issue to read next on top, two card edges fanned behind it,
 * then the series name and how much of it is read. Opens the run.
 */
@Composable
internal fun SeriesCard(stack: ShelfEntry.Stack, onOpen: (String) -> Unit, modifier: Modifier) {
    val top = stack.books.firstOrNull { it.readState == ReadState.IN_PROGRESS }
        ?: stack.books.firstOrNull { it.readState == ReadState.UNREAD }
        ?: stack.books.first()
    val read = stack.books.count { it.readState == ReadState.FINISHED }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button) { onOpen(stack.series) },
    ) {
        Column {
            Box(Modifier.padding(top = STACK_REVEAL, start = STACK_REVEAL, end = STACK_REVEAL)) {
                // The fanned edges: two plain cards peeking above the cover, smaller each step back.
                repeat(2) { depth ->
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                translationY = -STACK_REVEAL.toPx() * (depth + 1) / 2
                                scaleX = 1f - STACK_SHRINK * (depth + 1)
                            }
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 1f - EDGE_FADE * depth),
                            ),
                    )
                }
                BookCover(top, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            }
            Column(
                Modifier.padding(horizontal = Space.Row, vertical = Space.Gap),
                verticalArrangement = Arrangement.spacedBy(Space.Tight),
            ) {
                Text(
                    stack.series,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.library_series_count, stack.books.size, stack.books.size, read),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val STACK_REVEAL = 8.dp
private const val STACK_SHRINK = 0.06f
private const val EDGE_FADE = 0.3f
