package com.absolutex.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.absolutex.core.ui.A11y
import com.absolutex.model.TocEntry

private val PANEL_MAX_HEIGHT = 280.dp
private val INDENT_PER_DEPTH = 12.dp

/** Entries shown above the current one, so it reads in context rather than pinned to the top. */
private const val LEAD_IN = 2

/**
 * The book's contents (§5.2), from a PDF outline or an archive's folders. Sits above the rest of
 * the chrome, and picking an entry jumps to its page and closes the panel.
 */
@Composable
internal fun TocPanel(
    entries: List<TocEntry>,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The reader's page or chapter: its entry opens in view and reads bold. Null marks none. */
    current: Int? = null,
) {
    // The entry the reader is in: the last one starting at or before [current]. A 2,700-chapter
    // novel opened at chapter 1,900 must not open its contents at chapter 1.
    val here = current?.let { at -> entries.indexOfLast { it.pageIndex <= at } } ?: -1
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (here - LEAD_IN).coerceAtLeast(0))
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        LazyColumn(Modifier.heightIn(max = PANEL_MAX_HEIGHT), state = list) {
            items(entries.size) { index ->
                val entry = entries[index]
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (index == here) FontWeight.SemiBold else null,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Sized by its own padding, this cleared 48.dp only while the font scale
                        // did; a raw clickable gets none of the minimum a Material control does.
                        .heightIn(min = A11y.MinTouchTarget)
                        // Without a role TalkBack reads a chapter as static text, so the contents
                        // sound like a list of labels rather than a list of destinations.
                        .clickable(role = Role.Button) { onJump(entry.pageIndex) }
                        .padding(
                            start = 16.dp + INDENT_PER_DEPTH * entry.depth,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                )
            }
        }
    }
}
