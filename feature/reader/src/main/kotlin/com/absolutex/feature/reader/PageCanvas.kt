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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.core.decode.PageImage
import com.absolutex.core.decode.TileCache
import com.absolutex.core.decode.TileGrid
import com.absolutex.core.decode.TileKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private const val MAX_SCALE = 8f
private const val MIN_SCALE = 1f
/** Below this, the base layer already exceeds display density and tiles would add nothing. */
private const val TILE_THRESHOLD = 1.2f
/** Zoom callbacks only fire on crossings of this scale, to avoid per-frame recompose. */
private const val ZOOM_REPORT_THRESHOLD = 1.02f
/** Hysteresis is handled in ReaderScreen (1.05f); this is just the reporting gate. */
private fun crossesZoomBoundary(old: Float, new: Float): Boolean =
    (old <= ZOOM_REPORT_THRESHOLD) != (new <= ZOOM_REPORT_THRESHOLD)

private fun floorDiv(a: Int, b: Int): Int = if (a >= 0) a / b else -(((-a) + b - 1) / b)

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
    var lastReportedZoom by remember(pageIndex) { mutableFloatStateOf(1f) }

    val src = remember { Rect() }
    val dst = remember { Rect() }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }

    fun maybeReportZoom(newScale: Float) {
        if (crossesZoomBoundary(lastReportedZoom, newScale)) {
            lastReportedZoom = newScale
            onZoomChanged(newScale)
        }
    }

    // Base layer: decoded once at viewport size, kept resident for the whole page.
    LaunchedEffect(pageIndex, viewport) {
        val (vw, vh) = viewport
        if (vw <= 0 || vh <= 0) return@LaunchedEffect
        base = withContext(DecodeDispatchers.decode) {
            runCatching { page.decodeBase(vw, vh) }.getOrNull()
        }
    }

    // Tile streaming. Transform is observed via snapshotFlow so the composable body never
    // reads scale/offsetX/offsetY (which would recompose per gesture frame). Buckets are
    // computed in SOURCE space (offset/effective, not offset/TILE_SIZE) so a fixed source
    // region maps to a fixed bucket at any zoom; distinctUntilChangedBy keeps a steady
    // pinch from respawning the fetch on every frame.
    LaunchedEffect(pageIndex, viewport) {
        val (vw0, vh0) = viewport
        if (vw0 <= 0 || vh0 <= 0) return@LaunchedEffect
        if (page.width <= 0 || page.height <= 0) return@LaunchedEffect
        snapshotFlow { Triple(scale, offsetX, offsetY) }
            .distinctUntilChangedBy { (s, ox, oy) ->
                if (s < TILE_THRESHOLD) return@distinctUntilChangedBy Triple(0, 0, 0)
                val fit = min(vw0.toFloat() / page.width, vh0.toFloat() / page.height)
                val eff = fit * s
                if (eff <= 0f) return@distinctUntilChangedBy Triple(0, 0, 0)
                Triple(
                    (s * 4).toInt(),
                    (ox / eff / TileGrid.TILE_SIZE).toInt(),
                    (oy / eff / TileGrid.TILE_SIZE).toInt(),
                )
            }
            .collect { (s, ox, oy) ->
                ensureActive()
                if (s < TILE_THRESHOLD) return@collect

                val fit = min(vw0.toFloat() / page.width, vh0.toFloat() / page.height)
                val effective = fit * s
                if (effective <= 0f) return@collect

                // Inverse of the draw transform: draw uses
                // originX=(vw-drawW)/2+ox, so source left=((−ox)−(vw−drawW)/2)/effective.
                val drawW = page.width * effective
                val drawH = page.height * effective
                val left = (((-ox) - (vw0 - drawW) / 2f) / effective).toInt()
                val top = (((-oy) - (vh0 - drawH) / 2f) / effective).toInt()
                // +1 covers truncation of vw/effective (TileGrid API unchanged).
                val right = left + (vw0 / effective).toInt() + 1
                val bottom = top + (vh0 / effective).toInt() + 1

                val tiles = TileGrid.visibleTiles(
                    page.width, page.height, left, top, right, bottom, effective,
                )
                if (tiles.isEmpty()) return@collect
                // Nearest-to-viewport-center first, so the pixels under the eye land first.
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val ordered = tiles.sortedBy { t ->
                    val tcx = (t.left + t.right) / 2f
                    val tcy = (t.top + t.bottom) / 2f
                    (tcx - cx) * (tcx - cx) + (tcy - cy) * (tcy - cy)
                }
                var landed = false
                for (chunk in ordered.chunked(4)) {
                    ensureActive()
                    val decoded = coroutineScope {
                        chunk.map { t ->
                            async(DecodeDispatchers.decode) {
                                ensureActive()
                                val key = TileKey(pageIndex, t.col, t.row, t.sampleSize)
                                if (cache[key] != null) return@async null
                                val bmp = page.decodeTile(t) ?: return@async null
                                t to bmp
                            }
                        }.awaitAll()
                    }.filterNotNull()
                    for ((t, bmp) in decoded) {
                        cache.put(TileKey(pageIndex, t.col, t.row, t.sampleSize), bmp)
                        landed = true
                    }
                }
                if (landed) tileGeneration++
            }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it.width to it.height }
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
                            val old = scale
                            scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            if (scale <= MIN_SCALE) {
                                offsetX = 0f; offsetY = 0f
                            } else {
                                offsetX += pan.x; offsetY += pan.y
                                // Clamp so the image edge stays within the viewport.
                                val vw = size.width
                                val vh = size.height
                                if (vw > 0 && vh > 0 && page.width > 0 && page.height > 0) {
                                    val fit = min(vw.toFloat() / page.width, vh.toFloat() / page.height)
                                    val dw = page.width * fit * scale
                                    val dh = page.height * fit * scale
                                    val maxX = if (dw <= vw) 0f else (dw - vw) / 2f
                                    val maxY = if (dh <= vh) 0f else (dh - vh) / 2f
                                    offsetX = offsetX.coerceIn(-maxX, maxX)
                                    offsetY = offsetY.coerceIn(-maxY, maxY)
                                }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            if (old != scale) maybeReportZoom(scale)
                        } else if (scale > MIN_SCALE && pan != Offset.Zero) {
                            offsetX += pan.x
                            offsetY += pan.y
                            val vw = size.width
                            val vh = size.height
                            if (vw > 0 && vh > 0 && page.width > 0 && page.height > 0) {
                                val fit = min(vw.toFloat() / page.width, vh.toFloat() / page.height)
                                val dw = page.width * fit * scale
                                val dh = page.height * fit * scale
                                val maxX = if (dw <= vw) 0f else (dw - vw) / 2f
                                val maxY = if (dh <= vh) 0f else (dh - vh) / 2f
                                offsetX = offsetX.coerceIn(-maxX, maxX)
                                offsetY = offsetY.coerceIn(-maxY, maxY)
                            }
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
                        maybeReportZoom(scale)
                    },
                    onTap = { onTapCenter() },
                )
            },
    ) {
        val vw = size.width.toInt()
        val vh = size.height.toInt()

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
            if (cols <= 0 || rows <= 0) return@drawIntoCanvas
            // Visible col/row range from the same inverse transform as the fetch path
            // (TileGrid math, overscan 0): look up only on-screen keys instead of scanning
            // the whole columns×rows grid.
            val srcLeft = (((-ox) - (vw - drawW) / 2f) / effective).toInt()
            val srcTop = (((-oy) - (vh - drawH) / 2f) / effective).toInt()
            val srcRight = srcLeft + (vw / effective).toInt() + 1
            val srcBottom = srcTop + (vh / effective).toInt() + 1
            if (srcRight <= srcLeft || srcBottom <= srcTop) return@drawIntoCanvas
            val c0 = floorDiv(srcLeft, TileGrid.TILE_SIZE).coerceIn(0, cols - 1)
            val c1 = floorDiv(srcRight - 1, TileGrid.TILE_SIZE).coerceIn(0, cols - 1)
            val r0 = floorDiv(srcTop, TileGrid.TILE_SIZE).coerceIn(0, rows - 1)
            val r1 = floorDiv(srcBottom - 1, TileGrid.TILE_SIZE).coerceIn(0, rows - 1)
            if (c1 < c0 || r1 < r0) return@drawIntoCanvas
            for (row in r0..r1) {
                for (col in c0..c1) {
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
