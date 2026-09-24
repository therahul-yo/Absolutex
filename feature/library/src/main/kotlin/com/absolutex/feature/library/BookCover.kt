package com.absolutex.feature.library

import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
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
import com.absolutex.core.ui.Motion


/** Comic pages are taller than wide; 2:3 is the common trim. */
internal const val COVER_ASPECT = 2f / 3f


/**
 * A book's cover at its trim ratio, fading in when it arrives over a panel-tone ground.
 *
 * The fade answers something real — the art arriving — rather than decorating. The cover is
 * described for accessibility only while it is missing: once drawn, the row's title already says
 * which book it is, so reading the art out again is noise.
 */
@Composable
internal fun BookCover(
    book: LibraryBookUi,
    modifier: Modifier = Modifier,
    aspect: Float = COVER_ASPECT,
    shape: Shape = MaterialTheme.shapes.small,
) {
    val covers = LocalBookCovers.current
    // The width comes from onSizeChanged rather than BoxWithConstraints: that subcomposes every
    // card, and a library screen full of them cost ~77 ms of layout on the frame it came back.
    var widthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .aspectRatio(aspect)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .onSizeChanged { widthPx = it.width },
    ) {
        // Drawn first and always: the art fades in over it when it arrives, and a book with no
        // art (a text ebook, a hidden document cover, a failed decode) keeps it.
        CoverPlaceholder(book, showTitle = widthPx >= TITLE_MIN_WIDTH_PX)
        if (!book.showCover) return@Box
        // The key changes when the file does, so a replaced book never keeps its old cover.
        val key = "${book.path}|${book.sizeBytes}|${book.lastModified}"
        val art by produceState<ImageBitmap?>(null, key, widthPx) {
            if (widthPx > 0) value = covers.cover(book.path, key, widthPx)?.asImageBitmap()
        }
        val shown by animateFloatAsState(if (art == null) 0f else 1f, Motion.enter(), label = "cover")
        art?.let { current ->
            Image(
                current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(shown),
            )
        }
    }
}

/**
 * What a book looks like before, or without, its art: the kind of book as an icon and, where there
 * is room, its title set in type — a designed card, never an empty grey box.
 */
@Composable
private fun CoverPlaceholder(book: LibraryBookUi, showTitle: Boolean) {
    val missing = stringResource(R.string.library_cover_placeholder)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = missing },
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (book.isBook) Icons.Outlined.Description else Icons.Outlined.AutoStories,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (showTitle) 36.dp else 20.dp),
        )
        if (showTitle) {
            Text(
                book.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Narrower than this (a list row's thumbnail), the placeholder is the icon alone. */
private const val TITLE_MIN_WIDTH_PX = 240
