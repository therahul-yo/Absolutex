package com.absolutex.feature.reader

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import com.absolutex.core.ui.Motion
import kotlin.math.roundToInt

/**
 * Zoom for the continuous strip, as a document reader does it: the whole column zooms, and
 * while zoomed a finger pans across and down through the pages together. Before this a pinch
 * zoomed only the page under the fingers, which is a comic gesture, not a document one.
 *
 * Two layers, so it is both instant and sharp:
 * - During a pinch the strip is scaled as a picture ([pinch], a graphics layer about the
 *   fingers): no page is laid out or decoded again while the fingers move.
 * - When the fingers lift, the pinch is committed into [zoom], the width every page is laid out
 *   at, so each page renders at its real zoomed size; the scroll position and [offsetX] are
 *   moved so the point under the fingers stays exactly where it was.
 */
@Stable
internal class StripZoomState(private val list: LazyListState) {
    /** The width pages are laid out at, as a multiple of the screen's. */
    var zoom by mutableFloatStateOf(1f)
        private set

    /** The live pinch on top of [zoom], applied as a graphics layer until the fingers lift. */
    var pinch by mutableFloatStateOf(1f)
        private set

    /** The pinch's fixed point and its pan, in the strip's own pixels. */
    var pivot by mutableStateOf(Offset.Zero)
        private set
    var drift by mutableStateOf(Offset.Zero)
        private set

    /** How far the widened column is shifted left, 0 at its left edge. Always <= 0. */
    var offsetX by mutableFloatStateOf(0f)
        private set

    var width = 0
    var height = 0

    val zoomed: Boolean get() = zoom * pinch > ZOOMED_ABOVE

    fun pinchBy(factor: Float, centroid: Offset, pan: Offset) {
        if (pinch == 1f && drift == Offset.Zero) pivot = centroid
        pinch = (pinch * factor).coerceIn(MIN_ZOOM / zoom, MAX_STRIP_ZOOM / zoom)
        drift += pan
    }

    /** One finger, zoomed: moves the column sideways; the list scrolls itself vertically. */
    fun panBy(dx: Float) {
        offsetX = clampX(offsetX + dx, zoom)
    }

    /** Lays the pinch into the page width, keeping the point under the fingers where it is. */
    fun commit() {
        if (pinch == 1f && drift == Offset.Zero) return
        val next = (zoom * pinch).coerceIn(MIN_ZOOM, MAX_STRIP_ZOOM)
        val factor = next / zoom
        val x = pivot.x + (offsetX - pivot.x) * factor + drift.x
        // A content point at list offset y sits at screen y - scroll; scaling about the pivot and
        // adding the drift, the scroll that keeps it still is (scroll + pivot) * factor - pivot - drift.
        val scroll = (list.firstVisibleItemScrollOffset + pivot.y) * factor - pivot.y - drift.y
        list.requestScrollToItem(list.firstVisibleItemIndex, scroll.roundToInt().coerceAtLeast(0))
        zoom = next
        offsetX = clampX(x, next)
        pinch = 1f
        drift = Offset.Zero
    }

    /** Double-tap: fit to [DOUBLE_TAP_ZOOM], or back to fit, animated about the tapped point. */
    suspend fun toggleAt(at: Offset) {
        pivot = at
        drift = Offset.Zero
        val target = if (zoomed) MIN_ZOOM / zoom else DOUBLE_TAP_ZOOM / zoom
        animate(1f, target, animationSpec = tween(Motion.MEDIUM_MS, easing = Motion.Emphasized)) { value, _ ->
            pinch = value
        }
        commit()
    }

    private fun clampX(x: Float, atZoom: Float): Float =
        x.coerceIn(-(width * atZoom - width).coerceAtLeast(0f), 0f)
}

/** Pinch and sideways pan for the strip, taken before the pages or the list see them. */
internal fun Modifier.stripZoomGestures(state: StripZoomState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    pinching = true
                    val pan = event.calculatePan()
                    state.pinchBy(event.calculateZoom(), event.calculateCentroid(), pan)
                    // Ours alone: the list must not scroll and no page may zoom itself.
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } else if (!pinching && state.zoomed) {
                    // Sideways only; the list keeps the vertical part and scrolls as usual.
                    state.panBy(event.calculatePan().x)
                }
            } while (event.changes.any { it.pressed })
            if (pinching) state.commit()
        }
    }

/** The column, laid out [StripZoomState.zoom] times the screen's width and shifted by its pan. */
internal fun Modifier.stripZoomLayout(state: StripZoomState): Modifier =
    graphicsLayer {
        val pinch = state.pinch
        scaleX = pinch
        scaleY = pinch
        translationX = state.drift.x
        translationY = state.drift.y
        transformOrigin = if (size.width > 0f && size.height > 0f) {
            TransformOrigin(state.pivot.x / size.width, state.pivot.y / size.height)
        } else {
            TransformOrigin.Center
        }
    }.layout { measurable, constraints ->
        state.width = constraints.maxWidth
        state.height = constraints.maxHeight
        val wide = (constraints.maxWidth * state.zoom).roundToInt()
        val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(state.offsetX.roundToInt(), 0)
        }
    }

private const val MIN_ZOOM = 1f

/** Deep enough for a document's footnotes; beyond it tiles cost more than they show. */
private const val MAX_STRIP_ZOOM = 3f
private const val DOUBLE_TAP_ZOOM = 2f

/** Float noise above fit is still fit. */
private const val ZOOMED_ABOVE = 1.01f

/**
 * What a page in the strip needs from the strip: where to send a double-tap, and how wide its
 * base layer may be. [placed] is the strip's viewport, written on placement, to turn a page's
 * window position into the strip's own.
 */
internal class StripPage(
    val maxBaseWidth: Int,
    val onDoubleTap: (Offset) -> Unit,
    val onTap: (Offset) -> Unit,
    val placed: Array<LayoutCoordinates?>,
)

/**
 * [onTapAt] gets a tap in the strip's own pixels and the strip's size, to pick a tap zone from:
 * the screen's zones, whatever the page under the finger measures.
 */
@Composable
internal fun rememberStripPage(
    zoom: StripZoomState,
    scope: CoroutineScope,
    onTapAt: (x: Float, y: Float, width: Int, height: Int) -> Unit,
): StripPage {
    val density = LocalDensity.current
    val screenWidth = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val tapAt by rememberUpdatedState(onTapAt)
    return remember(zoom, screenWidth) {
        val placed = arrayOfNulls<LayoutCoordinates>(1)
        fun inStrip(inWindow: Offset): Pair<Offset, LayoutCoordinates>? =
            placed[0]?.let { strip -> strip.windowToLocal(inWindow) to strip }
        StripPage(
            maxBaseWidth = (screenWidth * BASE_WIDTH_OF_SCREEN).roundToInt(),
            onDoubleTap = { inWindow -> inStrip(inWindow)?.let { (at, _) -> scope.launch { zoom.toggleAt(at) } } },
            onTap = { inWindow ->
                inStrip(inWindow)?.let { (at, strip) -> tapAt(at.x, at.y, strip.size.width, strip.size.height) }
            },
            placed = placed,
        )
    }
}

/** A strip page's base layer, as a multiple of the screen width; tiles add detail beyond it. */
private const val BASE_WIDTH_OF_SCREEN = 1.25f
