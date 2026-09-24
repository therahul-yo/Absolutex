package com.absolutex.feature.reader

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Dark pages: the page's lightness inverted with its hues kept, so paper goes dark, ink goes
 * light, and a blue diagram stays blue rather than turning orange.
 *
 * Inverting then rotating hue half a turn folds into one matrix, `1 - 2·luma(c) + c` per channel.
 * The result is then squeezed into [PAPER]..[INK] so the page is never pure black against the
 * reader's black margins, nor its text a glaring white.
 *
 * A render effect on the page's own layer, not on the reader: the letterbox around the page is
 * outside it and stays black.
 */
internal fun Modifier.darkPages(on: Boolean): Modifier =
    if (on) graphicsLayer { renderEffect = DARK_PAGES } else this

private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f
private const val PAPER = 0.07f
private const val INK = 0.88f
private const val CHANNEL_MAX = 255f

private val DARK_PAGES = run {
    val k = INK - PAPER
    val offset = INK * CHANNEL_MAX
    fun row(r: Float, g: Float, b: Float) =
        floatArrayOf(k * (r - 2 * LUMA_R), k * (g - 2 * LUMA_G), k * (b - 2 * LUMA_B), 0f, offset)
    val matrix = ColorMatrix(row(1f, 0f, 0f) + row(0f, 1f, 0f) + row(0f, 0f, 1f) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix)).asComposeRenderEffect()
}
