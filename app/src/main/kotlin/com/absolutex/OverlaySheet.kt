package com.absolutex

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.absolutex.core.ui.Motion
import kotlin.coroutines.cancellation.CancellationException

/**
 * A screen shown as a sheet over the library rather than a destination that replaces it: an open
 * book rising from the bottom, or settings sliding in from the end edge.
 *
 * Closing a book used to navigate back to a library that had been torn down on the way in, so the
 * first frame of every close rebuilt the whole grid — 55–90 ms against an 8.3 ms budget, which is
 * the stutter the close animation had whatever curve it used. The library now stays composed
 * underneath; closing only moves this sheet off it.
 *
 * The back gesture drives the sheet with the finger (predictive back): it follows the swipe down
 * to [PEEK], and either finishes closing on release or springs back if the gesture is cancelled.
 * Only transforms change per frame, so the book itself is never recomposed by the motion.
 *
 * @param value what to show, or null for nothing. Changing it while shown swaps the content in
 *   place (a book's auto-advance); clearing it slides the sheet away and then drops its content.
 * @param fromEnd slide horizontally from the end edge (settings) instead of up from the bottom.
 * @param onGone called once the sheet has fully left the screen after a close.
 */
@Composable
internal fun <T : Any> OverlaySheet(
    value: T?,
    onClose: () -> Unit,
    onGone: () -> Unit = {},
    fromEnd: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val uri = value
    var shown by remember { mutableStateOf(value) }
    // 0 = covering the library, 1 = fully below the screen.
    val offset = remember { Animatable(if (uri == null) 1f else 0f) }
    LaunchedEffect(uri) {
        if (uri != null) {
            shown = uri
            offset.animateTo(0f, tween(Motion.LONG_MS, easing = Motion.Emphasized))
        } else if (shown != null) {
            offset.animateTo(1f, tween(Motion.MEDIUM_MS, easing = Motion.EmphasizedAccelerate))
            shown = null
            onGone()
        }
    }
    PredictiveBackHandler(enabled = uri != null) { gesture ->
        try {
            gesture.collect { event -> offset.snapTo(event.progress * PEEK) }
            onClose()
        } catch (e: CancellationException) {
            offset.animateTo(0f, tween(Motion.SHORT_MS))
            throw e
        }
    }
    val current = shown ?: return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (fromEnd) translationX = size.width * offset.value else translationY = size.height * offset.value
                // Rounded only while it moves: at rest the page runs edge to edge.
                val lifted = offset.value > 0f
                shape = RoundedCornerShape(if (lifted) CORNER else 0.dp)
                clip = lifted
            },
    ) { content(current) }
}

/** How far the sheet follows a back swipe before the release decides; enough to see the library. */
private const val PEEK = 0.35f

private val CORNER = 28.dp
