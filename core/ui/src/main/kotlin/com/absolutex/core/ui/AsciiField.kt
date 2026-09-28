package com.absolutex.core.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import android.graphics.Canvas as NativeCanvas

/**
 * A field of halftone characters drifting in slow waves — the landing page's background, in the
 * app.
 *
 * Painted off the main thread, at half resolution, into one of two bitmaps that swap when a new
 * one is ready; the main thread only ever draws the finished image. Painting the few thousand
 * glyphs on the main thread eleven times a second took a fifth of the frames of a page swiped
 * over it on the reference phone. The glyphs come from one monospace [Paint] on a fixed grid, so
 * columns cannot slip.
 *
 * Holds still while [paused] (say, while a pager slides over it) or when the system's animations
 * are off.
 */
@Composable
fun AsciiField(
    modifier: Modifier = Modifier,
    color: Color = Color(FIELD_GREY),
    paused: Boolean = false,
) {
    val density = LocalDensity.current
    val tint = remember(color) { ColorFilter.tint(color) }
    val paint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = with(density) { FIELD_SP.sp.toPx() } / SCALE
            this.color = android.graphics.Color.BLACK
        }
    }
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val hold by rememberUpdatedState(paused)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(size, paint, still) {
        val w = size.width / SCALE
        val h = size.height / SCALE
        if (w <= 0 || h <= 0) return@LaunchedEffect
        // Two buffers: one on screen, one being painted. Never a new bitmap per frame.
        // Alpha only, tinted when drawn: a quarter of the bytes to hand the GPU on each new frame.
        val buffers = Array(2) { Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8) }
        var next = 0
        var clock = 0f
        while (true) {
            val back = buffers[next]
            val t = clock
            withContext(Dispatchers.Default) { paintField(NativeCanvas(back), paint, w, h, t) }
            shown = back.asImageBitmap()
            next = 1 - next
            if (still) return@LaunchedEffect
            delay(TICK_MS)
            if (!hold) clock += TICK_MS / MS_PER_SECOND
            while (hold) delay(TICK_MS)
        }
    }
    Canvas(modifier.onSizeChanged { size = it }) {
        val image = shown ?: return@Canvas
        drawImage(
            image,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
            filterQuality = FilterQuality.Low,
            colorFilter = tint,
        )
    }
}

/** The field at time [t], every visible glyph on its grid cell. */
private fun paintField(canvas: NativeCanvas, paint: Paint, width: Int, height: Int, t: Float) {
    canvas.drawColor(0, PorterDuff.Mode.CLEAR)
    val cellW = paint.measureText("M")
    val metrics = paint.fontMetrics
    val cellH = metrics.descent - metrics.ascent
    if (cellW <= 0f || cellH <= 0f) return
    val cols = ceil(width / cellW).toInt()
    val rows = ceil(height / cellH).toInt()
    val glyph = CharArray(1)
    for (y in 0 until rows) {
        val v = y.toFloat() / rows
        val baseline = y * cellH - metrics.ascent
        for (x in 0 until cols) {
            val u = x.toFloat() / cols
            val a = sin(u * WAVE_U + t * SPEED_A) * cos(v * WAVE_V - t * SPEED_B)
            val b = sin((u * SKEW + v) * WAVE_D - t * SPEED_C)
            val lit = BASE + SPAN_A * a + SPAN_B * b
            val c = RAMP[floor(lit * RAMP.length).toInt().coerceIn(0, RAMP.length - 1)]
            if (c != ' ') {
                glyph[0] = c
                canvas.drawText(glyph, 0, 1, x * cellW, baseline, paint)
            }
        }
    }
}

/** Sparse on purpose: most of the field is blank, so it reads as texture behind words. */
private const val RAMP = "   ..··::-=+*"
private const val FIELD_GREY = 0xFF3A3A3A
private const val FIELD_SP = 11

/** Painted at this fraction of the screen's resolution: texture, not text anyone reads. */
private const val SCALE = 2
private const val TICK_MS = 90L
private const val MS_PER_SECOND = 1000f
private const val WAVE_U = 7f
private const val WAVE_V = 9f
private const val WAVE_D = 5f
private const val SKEW = 0.8f
private const val SPEED_A = 0.35f
private const val SPEED_B = 0.28f
private const val SPEED_C = 0.22f
private const val BASE = 0.42f
private const val SPAN_A = 0.34f
private const val SPAN_B = 0.24f
