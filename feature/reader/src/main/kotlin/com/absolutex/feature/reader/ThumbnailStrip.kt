package com.absolutex.feature.reader

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val THUMB_WIDTH = 56.dp
private val THUMB_HEIGHT = 84.dp
private val MARK_SIZE = 8.dp

/**
 * The chrome's thumbnail strip (§5.2): tap a page to jump to it, long-press to bookmark it, and a
 * dot marks the ones that are bookmarked.
 *
 * Thumbnails come from the reader's own pipeline, which caches them to disk, so scrolling the strip
 * of a 45-page book costs one decode per page ever, not per open. Only what is on screen is asked
 * for: a LazyRow composes the visible items, and each item's load dies with it.
 */
@Composable
internal fun ThumbnailStrip(
    pageCount: Int,
    page: Int,
    bookId: String,
    onSeek: (Int) -> Unit,
    thumbnail: suspend (index: Int, width: Int) -> Bitmap?,
    vm: BookmarksViewModel = hiltViewModel(),
) {
    val marks by remember(bookId) { vm.pages(bookId) }.collectAsStateWithLifecycle(emptyList())
    val state = rememberLazyListState()
    // Follows the page being read, so opening the chrome shows where you are rather than page 1.
    LaunchedEffect(page, bookId) { state.scrollToItem(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
    val widthPx = with(LocalDensity.current) { THUMB_WIDTH.roundToPx() }
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.height(THUMB_HEIGHT).padding(vertical = 4.dp),
    ) {
        items(pageCount) { index ->
            val bookmarked = index in marks
            ThumbnailCell(
                index = index,
                current = index == page,
                bookmarked = bookmarked,
                label = stringResource(thumbnailLabelRes(bookmarked), index + 1, pageCount),
                onTap = { onSeek(index) },
                onLongPress = { vm.toggle(bookId, index) },
                load = { thumbnail(index, widthPx) },
            )
        }
    }
}

@Composable
private fun ThumbnailCell(
    index: Int,
    current: Boolean,
    bookmarked: Boolean,
    label: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    load: suspend () -> Bitmap?,
) {
    // Keyed by index: a recycled cell must not show the previous page while its own decodes.
    val bitmap by produceState<Bitmap?>(null, index) { value = load() }
    val bookmarkActionLabel = stringResource(bookmarkActionRes(bookmarked))
    val colours = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(THUMB_WIDTH, THUMB_HEIGHT - 8.dp)
            .background(colours.surfaceVariant)
            .then(if (current) Modifier.border(2.dp, colours.primary) else Modifier)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .semantics {
                contentDescription = label
                // The border alone said which page you were on, which is nothing to a screen
                // reader. `selected` is announced by the platform, in the platform's language.
                selected = current
                // Long-press is not a gesture TalkBack offers, so without this the strip's
                // bookmarking is unreachable without sight — a feature, not a label problem.
                customActions = listOf(
                    CustomAccessibilityAction(bookmarkActionLabel) { onLongPress(); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
        if (bookmarked) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(MARK_SIZE)
                    .background(colours.primary, CircleShape),
            )
        }
    }
}

/**
 * Which description a cell carries. Bookmarked pages get their own sentence, so the fact is in
 * the announcement rather than only in a dot a screen reader cannot see.
 *
 * Split out so the choice is testable without rendering the strip: which string is picked is the
 * whole of the fix, and a composable cannot be asserted on from a unit test.
 */
@StringRes
internal fun thumbnailLabelRes(bookmarked: Boolean): Int =
    if (bookmarked) R.string.reader_page_indicator_desc_bookmarked
    else R.string.reader_page_indicator_desc

/** What the cell's accessibility action offers to do, which is the opposite of the current state. */
@StringRes
internal fun bookmarkActionRes(bookmarked: Boolean): Int =
    if (bookmarked) R.string.reader_bookmark_remove else R.string.reader_bookmark_add
