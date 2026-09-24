package com.absolutex.feature.library

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.tween


/** Comic pages are taller than wide; 2:3 is the common trim. */
internal const val COVER_ASPECT = 2f / 3f

private const val COVER_FADE_MS = 300

/**
 * A book's cover at its trim ratio, fading in when it arrives over a panel-tone ground.
 *
 * The fade answers something real — the art arriving — rather than decorating. The cover is
 * described for accessibility only while it is missing: once drawn, the row's title already says
 * which book it is, so reading the art out again is noise.
 */
@Composable
internal fun BookCover(book: LibraryBookUi, modifier: Modifier = Modifier) {
    val covers = LocalBookCovers.current
    val missing = stringResource(R.string.library_cover_placeholder)
    BoxWithConstraints(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val widthPx = constraints.maxWidth
        // The key changes when the file does, so a replaced book never keeps its old cover.
        val key = "${book.path}|${book.sizeBytes}|${book.lastModified}"
        val art by produceState<ImageBitmap?>(null, key, widthPx) {
            value = covers.cover(book.path, key, widthPx)?.asImageBitmap()
        }
        val shown by animateFloatAsState(if (art == null) 0f else 1f, tween(COVER_FADE_MS), label = "cover")
        val current = art
        if (current == null) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = missing },
            )
        } else {
            Image(
                current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(shown),
            )
        }
    }
}
