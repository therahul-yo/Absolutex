package com.absolutex.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Continue reading (§5.1): the comics you are part-way through, newest first, as a strip of small
 * covers across the top of the Comics tab, with a way on to the full Recent tab.
 *
 * The grid around it is already inset by [Space.Edge]; the strip's own row scrolls to the screen
 * edge (a negative margin undoes the inset) so covers slide out of view rather than being clipped
 * short of it, and its content padding puts the first cover back in line with the grid.
 */
@Composable
internal fun ContinueReadingStrip(
    books: List<LibraryBookUi>,
    context: RowContext,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(bottom = Space.Row)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.library_continue_reading), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.library_see_all)) }
        }
        LazyRow(
            modifier = Modifier.layout { measurable, constraints ->
                val edge = Space.Edge.roundToPx()
                val wide = constraints.copy(maxWidth = constraints.maxWidth + edge * 2)
                val placeable = measurable.measure(wide)
                layout(constraints.maxWidth, placeable.height) { placeable.place(-edge, 0) }
            },
            horizontalArrangement = Arrangement.spacedBy(Space.Row),
            contentPadding = PaddingValues(horizontal = Space.Edge),
        ) {
            items(books, key = { "continue:${it.path}" }) { book ->
                val tapOnly = context.copy(selectionActive = false, onToggleSelection = {})
                CoverCard(book, false, tapOnly, Modifier.width(ContinueWidth))
            }
        }
    }
}

private val ContinueWidth = 132.dp
internal const val CONTINUE_LIMIT = 12
