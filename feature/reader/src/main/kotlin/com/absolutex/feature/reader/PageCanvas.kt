package com.absolutex.feature.reader

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.core.decode.PageImage
import com.absolutex.core.decode.TileCache
import com.absolutex.core.decode.TileGrid
import com.absolutex.core.decode.TileKey
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private const val MAX_SCALE = 8f
private const val MIN_SCALE = 1f
/** Below this, the base layer already exceeds display density and tiles would add nothing. */
private const val TILE_THRESHOLD = 1.2f

/**
 * Draws one page: a resident low-res base layer with high-res tiles streamed over the viewport.
 *
 * Three decisions carry the 8.3 ms budget here:
 *
 * 1. Pan/zoom state is read ONLY inside the Canvas draw lambda. Compose then invalidates the
 *    draw phase alone — composition and layout are skipped entirely on every gesture frame.
 *    Reading these values in the composable body instead would recompose the whole subtree per
 *    frame and blow the budget on its own.
 * 2. Rect and Paint are hoisted with remember and mutated in place. Allocating per tile per
 *    frame is exactly the garbage the brief forbids in the scroll hot path.
 * 3. Tiles are drawn through nativeCanvas.drawBitmap, which accepts hardware bitmaps directly
 *    with no readback. That is what lets colour correction stay a draw-time shader later.
 */
@Composable
fun PageCanvas(
    page: PageImage,
    pageIndex: Int,
    cache: TileCache,
    modifier: Modifier = Modifier,
    onTapCenter: () -> Unit = {},
    /** Lets the pager stop stealing drags once the page is zoomed. */
    onZoomChanged: (Float) -> Unit = {},
) {
    var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableFloatStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableFloatStateOf(0f) }
    // Bumped when new tiles land, to invalidate the draw phase without touching composition.
    var tileGeneration by remember(pageIndex) { mutableIntStateOf(0) }
    var base by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var viewport by remember(pageIndex) { mutableStateOf(0 to 0) }

    val src = remember { Rect() }
    val dst = remember { Rect() }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }

    // Base layer: decoded once at viewport size, kept resident for the whole page.
    LaunchedEffect(pageIndex, viewport) {
        val (vw, vh) = viewport
        if (vw <= 0 || vh <= 0) return@LaunchedEffect
        base = withContext(DecodeDispatchers.decode) {
            runCatching { page.decodeBase(vw, vh) }.getOrNull()
        }
    }

    // Tile streaming. Keyed on the quantised transform so a steady pinch does not respawn this
    // on every frame; the redraw itself is driven by tileGeneration, not by recomposition.
    val zoomBucket = (scale * 4).toInt()
    val panBucketX = (offsetX / TileGrid.TILE_SIZE).toInt()
    val panBucketY = (offsetY / TileGrid.TILE_SIZE).toInt()
    LaunchedEffect(pageIndex, zoomBucket, panBucketX, panBucketY, viewport) {
        val (vw, vh) = viewport
        if (vw <= 0 || vh <= 0 || scale < TILE_THRESHOLD) return@LaunchedEffect
        if (page.width <= 0 || page.height <= 0) return@LaunchedEffect

        val fit = min(vw.toFloat() / page.width, vh.toFloat() / page.height)
        val effective = fit * scale
        if (effective <= 0f) return@LaunchedEffect

        // Viewport corners transformed back into source pixel space.
        val left = ((-offsetX) / effective).toInt()
        val top = ((-offsetY) / effective).toInt()
        val right = left + (vw / effective).toInt()
        val bottom = top + (vh / effective).toInt()

        val tiles = TileGrid.visibleTiles(
            page.width, page.height, left, top, right, bottom, effective,
        )
        var landed = false
        for (t in tiles) {
            val key = TileKey(pageIndex, t.col, t.row, t.sampleSize)
            if (cache[key] != null) continue
            val bmp = withContext(DecodeDispatchers.decode) { page.decodeTile(t) } ?: continue
            cache.put(key, bmp)
            landed = true
        }
        if (landed) tileGeneration++
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pageIndex) {
                // Hand-rolled instead of detectTransformGestures, which consumes EVERY drag
                // once past touch slop — that swallowed the horizontal swipe and the pager
                // never turned a page. Here a single-finger drag is only claimed while
                // zoomed in; at fit scale it falls through to HorizontalPager untouched.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break

                        val pressed = event.changes.count { it.pressed }
                        val pan = event.calculatePan()

                        if (pressed >= 2) {
                            // Pinch always belongs to us, at any scale.
                            val zoom = event.calculateZoom()
                            scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            if (scale <= MIN_SCALE) {
                                offsetX = 0f; offsetY = 0f
                            } else {
                                offsetX += pan.x; offsetY += pan.y
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            onZoomChanged(scale)
                        } else if (scale > MIN_SCALE && pan != Offset.Zero) {
                            offsetX += pan.x
                            offsetY += pan.y
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onDoubleTap = {
                        // Double-tap toggles between fit and a useful reading zoom.
                        if (scale > MIN_SCALE) {
                            scale = MIN_SCALE; offsetX = 0f; offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                        onZoomChanged(scale)
                    },
                    onTap = { onTapCenter() },
                )
            },
    ) {
        val vw = size.width.toInt()
        val vh = size.height.toInt()
        if (vw != viewport.first || vh != viewport.second) viewport = vw to vh

        // Draw-phase reads. Touching these here is what keeps gestures off the composition path.
        val s = scale
        val ox = offsetX
        val oy = offsetY
        @Suppress("UNUSED_EXPRESSION") tileGeneration

        val bmp = base ?: return@Canvas
        if (page.width <= 0 || page.height <= 0) return@Canvas

        val fit = min(vw.toFloat() / page.width, vh.toFloat() / page.height)
        val effective = fit * s
        val drawW = page.width * effective
        val drawH = page.height * effective
        val originX = (vw - drawW) / 2f + ox
        val originY = (vh - drawH) / 2f + oy

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas

            // Base layer always paints first, so a missing tile reveals a lower-res version of
            // the right pixels rather than a hole.
            src.set(0, 0, bmp.width, bmp.height)
            dst.set(
                originX.toInt(), originY.toInt(),
                (originX + drawW).toInt(), (originY + drawH).toInt(),
            )
            native.drawBitmap(bmp, src, dst, paint)

            if (s < TILE_THRESHOLD) return@drawIntoCanvas

            val sample = TileGrid.sampleSizeFor(effective)
            val cols = TileGrid.columns(page.width)
            val rows = TileGrid.rows(page.height)
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val tile = cache[TileKey(pageIndex, col, row, sample)] ?: continue
                    val tl = originX + col * TileGrid.TILE_SIZE * effective
                    val tt = originY + row * TileGrid.TILE_SIZE * effective
                    val tr = tl + tile.width * sample * effective
                    val tb = tt + tile.height * sample * effective
                    // Cull off-screen tiles before touching the canvas.
                    if (tr < 0 || tb < 0 || tl > vw || tt > vh) continue
                    src.set(0, 0, tile.width, tile.height)
                    dst.set(tl.toInt(), tt.toInt(), max(tr.toInt(), tl.toInt() + 1), max(tb.toInt(), tt.toInt() + 1))
                    native.drawBitmap(tile, src, dst, paint)
                }
            }
        }
    }
}
