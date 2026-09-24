package com.absolutex

import android.net.Uri
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
 * An open book, as a sheet over the library rather than a screen that replaces it.
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
 * @param uri the book to show, or null for none. Changing it while shown swaps the book in place
 *   (auto-advance); clearing it slides the sheet away and then drops its content.
 */
@Composable
internal fun BookSheet(uri: Uri?, onClose: () -> Unit, content: @Composable (Uri) -> Unit) {
    var shown by remember { mutableStateOf(uri) }
    // 0 = covering the library, 1 = fully below the screen.
    val offset = remember { Animatable(if (uri == null) 1f else 0f) }
    LaunchedEffect(uri) {
        if (uri != null) {
            shown = uri
            offset.animateTo(0f, tween(Motion.LONG_MS, easing = Motion.Emphasized))
        } else if (shown != null) {
            offset.animateTo(1f, tween(Motion.MEDIUM_MS, easing = Motion.EmphasizedAccelerate))
            shown = null
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
    val book = shown ?: return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = size.height * offset.value
                // Rounded only while it moves: at rest the page runs edge to edge.
                val lifted = offset.value > 0f
                shape = RoundedCornerShape(if (lifted) CORNER else 0.dp)
                clip = lifted
            },
    ) { content(book) }
}

/** How far the sheet follows a back swipe before the release decides; enough to see the library. */
private const val PEEK = 0.35f

private val CORNER = 28.dp
