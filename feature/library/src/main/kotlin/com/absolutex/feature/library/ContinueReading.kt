package com.absolutex.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.abs
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapPosition
import com.absolutex.core.ui.rememberHaptics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    hiddenVm: ContinueHiddenViewModel = hiltViewModel(),
) {
    val hidden by hiddenVm.hidden.collectAsStateWithLifecycle()
    val shown = books.filter { isShown(it, hidden) }
    // Everything taken off: no header over an empty strip.
    if (shown.isEmpty()) return
    Column(modifier.padding(bottom = Space.Row)) {
        // One line on one baseline, flush with the grid's edges: a quiet label and a plain link.
        // The TextButton's own padding pushed "See all" in from the edge and off the label's line.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.library_continue_reading).uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.library_see_all),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onSeeAll)
                    .padding(vertical = Space.Gap, horizontal = Space.Tight),
            )
        }
        ContinueCarousel(shown, context, onHide = { hiddenVm.hide(it) })
    }
}

/**
 * One book in the strip: its cover, how far in you are, and its name. Deliberately not the grid's
 * card: at strip width that card's footer (format pill beside the date) could not fit and wrapped,
 * and its surface squared the cover off against the card's edge. Here the cover is the card.
 */
@Composable
private fun ContinueCard(book: LibraryBookUi, onOpen: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val hideLabel = stringResource(R.string.library_continue_remove)
    Box(modifier) {
    Column(
        // Not clipped: the cover rounds its own corners, and a clip here cut into the last line.
        Modifier
            .combinedClickable(
                role = Role.Button,
                onClick = onOpen,
                onLongClick = {
                    haptics.confirm()
                    menu = true
                },
            )
            // Long-press is not a gesture TalkBack offers; the same action, reachable without it.
            .semantics { customActions = listOf(CustomAccessibilityAction(hideLabel) { onHide(); true }) },
        verticalArrangement = Arrangement.spacedBy(Space.Tight),
    ) {
        BookCover(book, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        book.progressFraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().padding(top = Space.Tight),
            )
        }
        Text(
            book.displayName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        book.progressLabel()?.let { progress ->
            Text(
                progress,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text(hideLabel) },
            leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
            onClick = {
                menu = false
                onHide()
            },
        )
    }
    }
}

private val ContinueWidth = 132.dp
internal const val CONTINUE_LIMIT = 12

/**
 * The strip as a wheel: the centred cover full size and facing the reader, its neighbours
 * shrinking, turning away and dropping along an arc as they leave the middle. A fling glides and
 * always settles with one cover centred, and each cover that reaches the middle clicks, so the
 * strip spins like a fidget toy rather than scrolling like the grid below it.
 *
 * Every transform is read in the layer block from the list's own layout, so scrolling moves
 * layers only: nothing recomposes while it spins.
 */
@Composable
private fun ContinueCarousel(books: List<LibraryBookUi>, context: RowContext, onHide: (String) -> Unit) {
    // A wheel has no ends: the covers repeat both ways and the strip opens in the middle of the
    // run, so there is a cover to either side from the first frame. One book cannot turn.
    val loops = books.size > 1
    val count = if (loops) books.size * LOOP_TURNS else books.size
    val list = rememberLazyListState(initialFirstVisibleItemIndex = if (loops) books.size * (LOOP_TURNS / 2) else 0)
    val haptics = rememberHaptics()
    val spacing = CarouselSpacing
    LaunchedEffect(list) {
        snapshotFlow { list.centredIndex() }.drop(1).distinctUntilChanged().collect { haptics.select() }
    }
    BoxWithConstraints(
        // Edge to edge: the grid around the strip is inset by Space.Edge, and a wheel clipped short
        // of the screen's edge reads as a box, not a wheel.
        Modifier.layout { measurable, constraints ->
            val edge = Space.Edge.roundToPx()
            val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + edge * 2))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-edge, 0) }
        },
    ) {
        // Room either side for the first and last cover to sit in the middle.
        val side = ((maxWidth - ContinueWidth) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = list,
            flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Center),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            contentPadding = PaddingValues(horizontal = side),
            modifier = Modifier.padding(vertical = CarouselLift),
        ) {
            items(count, key = { i -> "continue:$i:${books[i % books.size].path}" }) { i ->
                val book = books[i % books.size]
                ContinueCard(
                    book,
                    onOpen = { context.onOpen(book) },
                    onHide = { onHide(book.path) },
                    modifier = Modifier
                        .width(ContinueWidth)
                        .animateItem()
                        .graphicsLayer {
                            val d = list.offsetFromCentre(i, spacing.toPx())
                            val away = abs(d).coerceAtMost(MAX_AWAY)
                            val shrink = 1f - SHRINK * away
                            scaleX = shrink
                            scaleY = shrink
                            rotationY = -TURN_DEGREES * d.coerceIn(-MAX_AWAY, MAX_AWAY)
                            translationY = ARC.toPx() * away * away
                            translationX = -PULL.toPx() * d.coerceIn(-MAX_AWAY, MAX_AWAY)
                            cameraDistance = CAMERA * density
                        }
                        // Side covers dim under a black veil drawn over them. Not alpha: fading
                        // the card either composited it offscreen on every frame of a spin, or
                        // (per draw) let the placeholder under the cover show through it.
                        .drawWithContent {
                            drawContent()
                            val away = abs(list.offsetFromCentre(i, spacing.toPx())).coerceAtMost(MAX_AWAY)
                            if (away > 0f) drawRect(Color.Black.copy(alpha = FADE * away))
                        },
                )
            }
        }
    }
}

/** The index of the item whose centre is nearest the viewport's, or -1 with nothing laid out. */
private fun LazyListState.centredIndex(): Int {
    val info = layoutInfo
    val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - centre) }?.index ?: -1
}

/** How many card-widths item [index]'s centre is from the viewport's: 0 centred, negative left. */
private fun LazyListState.offsetFromCentre(index: Int, spacing: Float): Float {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 0f
    val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return (item.offset + item.size / 2f - centre) / (item.size + spacing)
}

private val CarouselSpacing = 4.dp

/** Times the covers repeat: enough that no fling reaches an end. */
private const val LOOP_TURNS = 400

/** Headroom for the arc: side covers drop, and must not be clipped by the row. */
private val CarouselLift = 6.dp
private val ARC = 10.dp
private val PULL = 14.dp

/** Beyond this many card-widths from the middle, a cover stops changing. */
private const val MAX_AWAY = 1.6f
private const val SHRINK = 0.16f
private const val FADE = 0.3f
private const val TURN_DEGREES = 24f
private const val CAMERA = 14f
