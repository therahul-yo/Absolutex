package com.absolutex.core.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A field of halftone characters drifting in slow waves — the landing page's background, in the
 * app.
 *
 * Drawn with the platform's own text call, one glyph per grid cell from a single monospace
 * [Paint]: a few thousand cheap draws. (Compose's laid-out text draws per glyph cost several
 * times that, and a page swiped over the field stuttered.) The grid is fixed, so columns cannot
 * slip whichever font a glyph comes from.
 *
 * About eleven frames a second, and not at all while [paused] (say, while a pager slides over
 * it) or when the system's animations are off.
 */
@Composable
fun AsciiField(
    modifier: Modifier = Modifier,
    color: Color = Color(FIELD_GREY),
    paused: Boolean = false,
) {
    val density = LocalDensity.current
    val paint = remember(color, density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = with(density) { FIELD_SP.sp.toPx() }
            this.color = color.toArgb()
        }
    }
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val hold by rememberUpdatedState(paused)
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = 0L
        var clock = 0f
        while (true) {
            withFrameNanos { now ->
                if (now - last > FRAME_NANOS) {
                    // Paused time does not pass, so the field resumes where it stopped.
                    if (!hold && last != 0L) clock += (now - last) / NANOS_PER_SECOND
                    last = now
                    if (!hold) time = clock
                }
            }
        }
    }
    // Its own layer: the field's drawing is recorded once per tick and replayed while anything
    // over it moves. Sharing the parent's layer, every frame of a page swipe redrew every glyph.
    Canvas(modifier.graphicsLayer()) {
        val cellW = paint.measureText("M")
        val metrics = paint.fontMetrics
        val cellH = metrics.descent - metrics.ascent
        if (cellW <= 0f || cellH <= 0f) return@Canvas
        val cols = ceil(size.width / cellW).toInt()
        val rows = ceil(size.height / cellH).toInt()
        val t = time
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val glyph = CharArray(1)
            for (y in 0 until rows) {
                val v = y.toFloat() / rows
                val baseline = y * cellH - metrics.ascent
                for (x in 0 until cols) {
                    val u = x.toFloat() / cols
                    val a = sin(u * WAVE_U + t * SPEED_A) * cos(v * WAVE_V - t * SPEED_B)
                    val b = sin((u * SKEW + v) * WAVE_D - t * SPEED_C)
                    val lit = BASE + SPAN_A * a + SPAN_B * b
                    val index = floor(lit * RAMP.length).toInt().coerceIn(0, RAMP.length - 1)
                    val c = RAMP[index]
                    if (c != ' ') {
                        glyph[0] = c
                        native.drawText(glyph, 0, 1, x * cellW, baseline, paint)
                    }
                }
            }
        }
    }
}

/** Sparse on purpose: most of the field is blank, so it reads as texture behind words. */
private const val RAMP = "   ..··::-=+*"
private const val FIELD_GREY = 0xFF3A3A3A
private const val FIELD_SP = 11
private const val FRAME_NANOS = 90_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000f
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
