package com.absolutex.feature.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.os.Trace
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.core.decode.PageImage
import com.absolutex.core.decode.TileCache
import com.absolutex.core.decode.TileGrid
import com.absolutex.core.decode.TileKey
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.ColourPipeline
import com.absolutex.model.FitGeometry
import com.absolutex.model.FitMode
import com.absolutex.model.TapGrid
import com.absolutex.model.TapZone
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MAX_SCALE = 8f
private const val MIN_SCALE = 1f
/** Below this, the base layer already exceeds display density and tiles would add nothing. */
private const val TILE_THRESHOLD = 1.2f
/** Above this zoom the page owns every drag and the pager is locked; a hair above 1 absorbs float noise. */
private const val ZOOM_LOCK_THRESHOLD = 1.02f

/** Double-tap zoom from fit: large enough to read small lettering, small enough to keep context. */
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * The base layer's longest edge, as a multiple of the screen's. Full size on a 12000px page would
 * otherwise be a texture of hundreds of MB; tiles cover the detail the cap leaves out.
 */
private const val MAX_BASE_EDGE = 1.25f

/** Zoom quantisation for the tile-fetch trigger: quarter steps of scale. */
private const val ZOOM_BUCKETS_PER_UNIT = 4

/** Tiles decoded per batch, so the nearest ones land before the whole ring is done. */
private const val TILE_FETCH_CHUNK = 4

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
 * 4. Colour correction (§4) is a RuntimeShader on the tile Paint, fed by one BitmapShader per
 *    bitmap — never a RenderEffect on the page layer, which would force the layer's contents
 *    through a separate offscreen pass. At neutral ([ColourParams.isNeutral]) the shader path
 *    is not entered at all: the draw calls below are the pre-shader ones, so correction off
 *    costs nothing by construction.
 */
@Composable
fun PageCanvas(
    page: PageImage,
    pageIndex: Int,
    bookId: String,
    cache: TileCache,
    modifier: Modifier = Modifier,
    fitMode: FitMode = FitMode.FIT_SCREEN,
    /** Where an overflowing page starts: its right edge for a right-to-left book. */
    rightToLeft: Boolean = false,
    /** The tapped zone of §5.2's 3x3 grid, already mirrored for a right-to-left book. */
    onTapZone: (TapZone) -> Unit = {},
    /**
     * The base layer is decoded and the next frame paints it. This is what "first page rendered"
     * means for §3's budget — the page object existing is not the same as pixels.
     */
    onBaseReady: () -> Unit = {},
    /**
     * Supplies the base layer at a size. The reader passes one that shares a decode per page and
     * size; the default decodes directly, for previews and tests.
     */
    /** Zoom factors from the keyboard or gamepad (§5.3), applied about the screen centre. */
    zoomSteps: Flow<Float>? = null,
    baseLayer: suspend (width: Int, height: Int) -> Bitmap? = { w, h ->
        withContext(DecodeDispatchers.decode) { runCatching { page.decodeBase(w, h) }.getOrNull() }
    },
    /** The pager's axis. A page that can scroll along it must own drags on it. */
    pagerVertical: Boolean = false,
    /**
     * True while the pager must stay off this page's drags: the page is zoomed, or overflows along
     * the pager's axis. Consuming a drag here does not stop the pager — it starts dragging before
     * this node decides — so the pager has to be switched off instead. Reported on change only.
     */
    onPagerLockChanged: (Boolean) -> Unit = {},
    /**
     * A swipe along the pager's axis that the page cannot absorb while the pager is locked, which is
     * the only way such a page turns. `forward` is the finger moving left (or up).
     */
    onEdgeSwipe: (forward: Boolean) -> Unit = {},
    /** Which half of a two-page spread this page fills, if any. */
    spreadSide: SpreadSide = SpreadSide.NONE,
    /**
     * Draw-time colour correction (§4). Neutral by default, which leaves the draw calls below
     * exactly as they were — ReaderScreen passes nothing today and needs no change until
     * milestone 2 wires RenderingPrefs through here (TODO(lead): add that parameter then).
     */
    colour: ColourParams = ColourParams(),
) {
    var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableFloatStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableFloatStateOf(0f) }
    // Bumped when new tiles land, to invalidate the draw phase without touching composition.
    var tileGeneration by remember(pageIndex) { mutableIntStateOf(0) }
    var base by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var viewport by remember(pageIndex) { mutableStateOf(0 to 0) }
    // Null until first reported, so a page re-entering composition always clears a stale lock.
    var lastReportedLock by remember(pageIndex) { mutableStateOf<Boolean?>(null) }
    // The layout (fit mode, flow) this page was last put at its start position for. A viewport change
    // under the same layout only re-clamps, so a status-bar toggle or rotation never throws the
    // reader back to the top of a page they had scrolled down.
    var positionedFor by remember(pageIndex) { mutableStateOf<Pair<FitMode, Boolean>?>(null) }
    // The gesture coroutine outlives recompositions; read the callbacks through these so a flow change
    // mid-page cannot leave it turning pages with the old direction.
    val edgeSwipe by rememberUpdatedState(onEdgeSwipe)
    val lockChanged by rememberUpdatedState(onPagerLockChanged)

    // Plain holder: tracing the first successful draw must not invalidate composition or draw.
    val firstDraw = remember(bookId, pageIndex) { booleanArrayOf(true) }
    val src = remember { Rect() }
    val dst = remember { Rect() }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    // Draw-time colour state. Hoisted like Rect/Paint above: uniforms update in place per frame,
    // nothing here allocates per frame, per tile or per draw call.
    val colourPipeline = remember(pageIndex) { ColourPipeline() }
    // Macrobenchmark hook: GpuBenchmark launches the reader with EXTRA_COLOUR on the intent to
    // drive the corrected path with no settings UI. Read once per page, never per frame; the
    // draw lambda below only reads the resolved value. Gated on FLAG_DEBUGGABLE so the hook is
    // inert in release builds — no intent extra is read at all outside a debuggable build.
    // TODO(lead): milestone 2 replaces this with RenderingPrefs passed as `colour` above.
    val context = LocalContext.current
    val benchmarkColour = remember(pageIndex) {
        val appInfo = context.applicationInfo
        if (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            null
        } else {
            (context as? Activity)?.intent?.getStringExtra(ColourParams.EXTRA_COLOUR)
                ?.let(ColourParams::decode)
        }
    }

    fun reportLock(atScale: Float, vw: Int, vh: Int) {
        val s = FitGeometry.baseScale(fitMode, vw, vh, page.width, page.height)
        val overflowsPagerAxis = if (pagerVertical) {
            FitGeometry.maxOffsetY(vh, page.height, s) > 0f
        } else {
            FitGeometry.maxOffsetX(vw, page.width, s) > 0f
        }
        val locked = atScale > ZOOM_LOCK_THRESHOLD || overflowsPagerAxis
        if (locked != lastReportedLock) {
            lastReportedLock = locked
            lockChanged(locked)
        }
    }

    /**
     * Size the base layer decodes at: what the page is drawn at for [fitMode] at zoom 1, never above
     * source resolution, capped by [MAX_BASE_EDGE]. Decoding at the viewport box instead left fit
     * height and full size upscaled from a smaller bitmap and permanently soft, since tiles only
     * start once the user zooms.
     */
    fun baseTarget(vw: Int, vh: Int): Pair<Int, Int> {
        val atFit = min(FitGeometry.baseScale(fitMode, vw, vh, page.width, page.height), 1f)
        val longest = max(page.width, page.height) * atFit
        val cap = MAX_BASE_EDGE * max(vw, vh)
        val k = atFit * if (longest > cap) cap / longest else 1f
        return max(1, (page.width * k).toInt()) to max(1, (page.height * k).toInt())
    }

    // Reading starts at the top of an overflowing page, on the edge its flow starts from. This runs
    // before the base layer lands (that effect decodes first), so the page never flashes centred.
    LaunchedEffect(pageIndex, viewport, fitMode, rightToLeft, pagerVertical) {
        val (vw, vh) = viewport
        if (vw <= 0 || vh <= 0) return@LaunchedEffect
        reportLock(scale, vw, vh)
        val layout = fitMode to rightToLeft
        if (positionedFor != layout) {
            // New page or a deliberate layout change: start where that layout starts, at the current zoom.
            val s = FitGeometry.baseScale(fitMode, vw, vh, page.width, page.height) * scale
            offsetX = FitGeometry.startOffsetX(vw, page.width, s, rightToLeft)
            offsetY = FitGeometry.startOffsetY(vh, page.height, s)
            positionedFor = layout
        } else {
            val clamped = clampOffset(Offset(offsetX, offsetY), vw, vh, page.width, page.height, fitMode, scale)
            offsetX = clamped.x; offsetY = clamped.y
        }
    }

    LaunchedEffect(zoomSteps, viewport) {
        val steps = zoomSteps ?: return@LaunchedEffect
        val (vw, vh) = viewport
        if (vw <= 0 || vh <= 0) return@LaunchedEffect
        steps.collect { factor ->
            val old = scale
            scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            val ratio = scale / old
            val clamped = clampOffset(
                Offset(offsetX * ratio, offsetY * ratio), vw, vh, page.width, page.height, fitMode, scale,
            )
            offsetX = clamped.x; offsetY = clamped.y
            reportLock(scale, vw, vh)
        }
    }

    // Base layer: decoded once at its drawn size, kept resident for the whole page.
    LaunchedEffect(pageIndex, viewport, fitMode) {
        val (vw, vh) = viewport
        if (vw <= 0 || vh <= 0) return@LaunchedEffect
        val (tw, th) = baseTarget(vw, vh)
        base = baseLayer(tw, th)
        // Success or failure, the page has reached a stable state; reporting only on success left a
        // book whose first base failed never "fully drawn", which starves the startup metric.
        onBaseReady()
    }

    /**
     * Whether a one-finger drag heading along [travel] can move the page: only along an axis that
     * overflows at the current zoom, and only with room left in that direction.
     */
    fun canPan(travel: Offset, vw: Int, vh: Int): Boolean {
        val s = FitGeometry.baseScale(fitMode, vw, vh, page.width, page.height) * scale
        return if (abs(travel.x) >= abs(travel.y)) {
            val limit = FitGeometry.maxOffsetX(vw, page.width, s)
            if (travel.x > 0) offsetX < limit else offsetX > -limit
        } else {
            val limit = FitGeometry.maxOffsetY(vh, page.height, s)
            if (travel.y > 0) offsetY < limit else offsetY > -limit
        }
    }

    fun alongPagerAxis(travel: Offset): Boolean =
        if (pagerVertical) abs(travel.y) > abs(travel.x) else abs(travel.x) >= abs(travel.y)

    /** Decides a one-finger drag once, at touch slop. True when the page claims it. */
    fun decideAtSlop(travel: Offset, vw: Int, vh: Int): Boolean {
        if (canPan(travel, vw, vh)) return true
        // With the pager locked nothing else can turn the page, so the swipe the page could not
        // absorb turns it from here.
        if (alongPagerAxis(travel) && lastReportedLock == true) {
            edgeSwipe(if (pagerVertical) travel.y < 0 else travel.x < 0)
        }
        return false
    }

    // Tile streaming. Transform is observed via snapshotFlow so the composable body never
    // reads scale/offsetX/offsetY (which would recompose per gesture frame). Buckets are
    // computed in SOURCE space (offset/effective, not offset/TILE_SIZE) so a fixed source
    // region maps to a fixed bucket at any zoom; distinctUntilChangedBy keeps a steady
    // pinch from respawning the fetch on every frame.
    LaunchedEffect(pageIndex, viewport, fitMode) {
        val (vw0, vh0) = viewport
        if (vw0 <= 0 || vh0 <= 0) return@LaunchedEffect
        if (page.width <= 0 || page.height <= 0) return@LaunchedEffect
        val fit = FitGeometry.baseScale(fitMode, vw0, vh0, page.width, page.height)
        // Tiles start once the drawn page outresolves the base layer, not at a fixed zoom: fit
        // width and full size draw above the base's resolution at zoom 1 when MAX_BASE_EDGE caps it.
        val baseWidth = baseTarget(vw0, vh0).first
        // A base layer already at source resolution leaves tiles nothing to add.
        if (baseWidth >= page.width) return@LaunchedEffect
        val tilesFrom = baseWidth.toFloat() / page.width * TILE_THRESHOLD
        snapshotFlow { Triple(scale, offsetX, offsetY) }
            .distinctUntilChangedBy { (s, ox, oy) ->
                val eff = fit * s
                if (eff <= 0f || eff < tilesFrom) return@distinctUntilChangedBy Triple(0, 0, 0)
                Triple(
                    (s * ZOOM_BUCKETS_PER_UNIT).toInt(),
                    (ox / eff / TileGrid.TILE_SIZE).toInt(),
                    (oy / eff / TileGrid.TILE_SIZE).toInt(),
                )
            }
            .collect { (s, ox, oy) ->
                ensureActive()
                val effective = fit * s
                if (effective <= 0f || effective < tilesFrom) return@collect

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
                for (chunk in ordered.chunked(TILE_FETCH_CHUNK)) {
                    ensureActive()
                    val decoded = coroutineScope {
                        chunk.map { t ->
                            async(DecodeDispatchers.decode) {
                                ensureActive()
                                val key = TileKey(pageIndex, t.col, t.row, t.sampleSize, bookId)
                                if (cache[key] != null) return@async null
                                val bmp = page.decodeTile(t) ?: return@async null
                                t to bmp
                            }
                        }.awaitAll()
                    }.filterNotNull()
                    for ((t, bmp) in decoded) {
                        cache.put(TileKey(pageIndex, t.col, t.row, t.sampleSize, bookId), bmp)
                        landed = true
                    }
                    // Bump per chunk, not once at the end. Cancellation is normal here — any
                    // pan or zoom restarts this collect — and a bump deferred to the end is
                    // lost on cancel, leaving decoded tiles sitting in the cache unpainted
                    // until some later change happens to redraw.
                    if (landed) {
                        tileGeneration++
                        landed = false
                    }
                }
            }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it.width to it.height }
            .pointerInput(pageIndex, fitMode, pagerVertical) {
                // Hand-rolled instead of detectTransformGestures, which consumes EVERY drag
                // once past touch slop — that swallowed the horizontal swipe and the pager
                // never turned a page. A one-finger drag is decided once, at touch slop: claimed
                // if it can move the page along the axis it is heading (see decideAtSlop). Along the
                // pager's own axis this node only ever sees drags while the pager is locked; the
                // cross axis (a fit-width page scrolling down inside a horizontal pager) is shared,
                // and deciding at slop keeps an early vertical wobble from panning a page turn.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var travel = Offset.Zero
                    var claimed = false
                    // Set when the page declines a drag at slop. It stops panning but keeps watching,
                    // so a second finger that lands late still pinches.
                    var ceded = false
                    // Set when a declined drag runs along a free pager's axis: the pager is turning the
                    // page, and a pinch now would fight its drag, so the rest of the gesture is its.
                    var released = false
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
                            // Scaling the offset with the zoom keeps the point at screen centre
                            // still; the clamp keeps the page on screen, and at zoom 1 pulls a
                            // fitting page back to centre.
                            val ratio = scale / old
                            val clamped = clampOffset(
                                Offset(offsetX * ratio + pan.x, offsetY * ratio + pan.y),
                                size.width, size.height, page.width, page.height, fitMode, scale,
                            )
                            offsetX = clamped.x; offsetY = clamped.y
                            claimed = true
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            if (old != scale) reportLock(scale, size.width, size.height)
                        } else if (pan != Offset.Zero) {
                            if (!claimed && !ceded) {
                                travel += pan
                                if (travel.getDistance() >= viewConfiguration.touchSlop) {
                                    claimed = decideAtSlop(travel, size.width, size.height)
                                    ceded = !claimed
                                    released = ceded && lastReportedLock != true && alongPagerAxis(travel)
                                }
                            }
                            if (claimed) {
                                val clamped = clampOffset(
                                    Offset(offsetX + pan.x, offsetY + pan.y),
                                    size.width, size.height, page.width, page.height, fitMode, scale,
                                )
                                offsetX = clamped.x; offsetY = clamped.y
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (!released && event.changes.any { it.pressed })
                }
            }
            // rightToLeft is a key: onTap mirrors the grid by it, and a flow change mid-page would
            // otherwise leave taps turning pages the old way while swipes already go the new way.
            .pointerInput(pageIndex, fitMode, pagerVertical, rightToLeft, spreadSide) {
                detectTapGestures(
                    onDoubleTap = {
                        // Double-tap toggles between fit and a useful reading zoom, about the
                        // screen centre, so the passage being read stays where it was.
                        val old = scale
                        scale = if (scale > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE
                        val ratio = scale / old
                        val clamped = clampOffset(
                            Offset(offsetX * ratio, offsetY * ratio),
                            size.width, size.height, page.width, page.height, fitMode, scale,
                        )
                        offsetX = clamped.x; offsetY = clamped.y
                        reportLock(scale, size.width, size.height)
                    },
                    onTap = { at ->
                        // Mirrored for RTL, so "the column that turns forward" stays under the same
                        // thumb whichever way the book reads.
                        onTapZone(spreadSide.zoneAt(at.x, at.y, size.width, size.height, rightToLeft))
                    },
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
        // Colour resolution is a draw-phase read too: a slider drag (milestone 2) repaints without
        // recomposing. Null means neutral — the draw calls below are then today's, untouched.
        val activeColour = benchmarkColour ?: colour
        val graded = if (colourPipeline.shouldApply(activeColour)) activeColour else null

        val bmp = base ?: return@Canvas
        if (page.width <= 0 || page.height <= 0) return@Canvas

        val fit = FitGeometry.baseScale(fitMode, vw, vh, page.width, page.height)
        val effective = fit * s
        val drawW = page.width * effective
        val drawH = page.height * effective
        // A page narrower than its half of a spread moves its spare width to the outside edge, so
        // facing pages meet at the gutter. An overflowing page has none and stays centred.
        val originX = (vw - drawW) / 2f + ox + spreadSide.gutter * max(0f, (vw - drawW) / 2f)
        val originY = (vh - drawH) / 2f + oy

        val traceFirstDraw = firstDraw[0]
        if (traceFirstDraw) Trace.beginSection("absx.firstDraw")
        try {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas

            // Base layer always paints first, so a missing tile reveals a lower-res version of
            // the right pixels rather than a hole.
            src.set(0, 0, bmp.width, bmp.height)
            dst.set(
                originX.toInt(), originY.toInt(),
                (originX + drawW).toInt(), (originY + drawH).toInt(),
            )
            if (graded != null) {
                drawGraded(native, colourPipeline, graded, bmp, dst.left, dst.top, dst.right, dst.bottom)
            } else {
                native.drawBitmap(bmp, src, dst, paint)
            }

            // The base layer's own resolution decides, read off the bitmap: no allocation here.
            if (bmp.width >= page.width) return@drawIntoCanvas
            if (effective < bmp.width.toFloat() / page.width * TILE_THRESHOLD) return@drawIntoCanvas

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
                    val tile = cache[TileKey(pageIndex, col, row, sample, bookId)] ?: continue
                    val tl = originX + col * TileGrid.TILE_SIZE * effective
                    val tt = originY + row * TileGrid.TILE_SIZE * effective
                    val tr = tl + tile.width * sample * effective
                    val tb = tt + tile.height * sample * effective
                    // Cull off-screen tiles before touching the canvas.
                    if (tr < 0 || tb < 0 || tl > vw || tt > vh) continue
                    src.set(0, 0, tile.width, tile.height)
                    dst.set(tl.toInt(), tt.toInt(), max(tr.toInt(), tl.toInt() + 1), max(tb.toInt(), tt.toInt() + 1))
                    if (graded != null) {
                        drawGraded(native, colourPipeline, graded, tile, dst.left, dst.top, dst.right, dst.bottom)
                    } else {
                        native.drawBitmap(tile, src, dst, paint)
                    }
                }
            }
        }
        } finally {
            if (traceFirstDraw) {
                firstDraw[0] = false
                Trace.endSection()
            }
        }
    }
}

/**
 * One bitmap through the draw-time colour shader.
 *
 * drawRect, not drawBitmap: Skia replaces a paint's shader with the image shader on bitmap
 * draws, so a RuntimeShader on the paint would silently never run. The child BitmapShader
 * instead carries the dst-to-bitmap matrix (see contentMatrix), which makes this cover exactly
 * the pixels drawBitmap would. A top-level function, not a local one: a capturing local would
 * allocate its closure object on every frame.
 */
private fun drawGraded(
    native: android.graphics.Canvas,
    pipeline: ColourPipeline,
    params: ColourParams,
    bitmap: Bitmap,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
) {
    val paint = pipeline.paintFor(params, bitmap, left, top, right, bottom)
    native.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
}

/**
 * Keeps the drawn page's edges inside the viewport, so a pan cannot strand the page off screen.
 * Returns the offset unchanged when either the viewport or the page has no size yet.
 */
private fun clampOffset(
    offset: Offset,
    viewportW: Int,
    viewportH: Int,
    pageW: Int,
    pageH: Int,
    fitMode: FitMode,
    scale: Float,
): Offset {
    if (viewportW <= 0 || viewportH <= 0) return offset
    if (pageW <= 0 || pageH <= 0) return offset
    val s = FitGeometry.baseScale(fitMode, viewportW, viewportH, pageW, pageH) * scale
    val maxX = FitGeometry.maxOffsetX(viewportW, pageW, s)
    val maxY = FitGeometry.maxOffsetY(viewportH, pageH, s)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * Where a page sits in a two-page spread, in screen terms. Each page gets half the screen as its
 * viewport, but its tap zones stay those of the whole screen: the thumb that turns a single page
 * forward must turn a spread forward too.
 */
enum class SpreadSide(internal val gutter: Float) {
    NONE(0f),
    LEFT(1f),
    RIGHT(-1f),
    ;

    internal fun zoneAt(x: Float, y: Float, width: Int, height: Int, mirrored: Boolean): TapZone = when (this) {
        NONE -> TapGrid.zoneAt(x, y, width, height, mirrored)
        LEFT -> TapGrid.zoneAt(x, y, width * 2, height, mirrored)
        RIGHT -> TapGrid.zoneAt(x + width, y, width * 2, height, mirrored)
    }
}
