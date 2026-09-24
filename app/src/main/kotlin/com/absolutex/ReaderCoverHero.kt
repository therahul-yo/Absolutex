package com.absolutex

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.ui.Motion
import com.absolutex.feature.library.LocalBookCovers
import com.absolutex.feature.library.LocalCoverTransitionHost
import com.absolutex.feature.library.isCoverHeroReady
import com.absolutex.feature.reader.ReaderViewModel

/**
 * The reader end of the cover shared element: the open book's art, grown from its grid card.
 *
 * The reader shows pages, not a cover header, so there is no permanent destination to morph
 * into — this hero stands in for one. It covers the decoding window (the spinner it replaces
 * would otherwise be the first thing seen), fades once the book settles, and fades back in at
 * the start of a close so the return flight has somewhere to fly from. Both directions key by
 * the same [coverPathFor] string the source card hides by, so open and close are one element.
 *
 * @param closing true once the sheet has been asked to close but before it is gone: the hero
 *   returns over the pages, and [onSettledVisible] fires once it is fully back — the parent
 *   flips the source card on in the same frame it drops this hero, which is the handoff the
 *   return flight runs on. Sequenced rather than simultaneous so the two ends are never both
 *   drawn at once.
 * @param handedOff true after that handoff: the hero leaves the flight at once (the flight's
 *   overlay draws the cover from here) instead of fading, which would double-draw it.
 *
 * The hero stays composed the whole time its sheet is up — transparent and unshared while the
 * book is being read — so its decoded art survives for the close fade. Dropping composition
 * when the book settled would re-extract the cover (a PDF render plus JPEG round trip) on the
 * very frames the close flight runs on; retaining one grid-size bitmap costs nothing next to
 * that.
 */
@Composable
internal fun ReaderCoverHero(
    uri: Uri,
    closing: Boolean,
    handedOff: Boolean,
    onSettledVisible: () -> Unit,
    vm: ReaderViewModel,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Per book, so opening book B over stale settled state from book A cannot retire the hero
    // before B has even started loading — the hero must be seen covering each book's window.
    var seenLoading by remember(uri) { mutableStateOf(false) }
    if (ui.loading) seenLoading = true
    val ready = isCoverHeroReady(seenLoading, ui.loading, ui.error, ui.pageCount, ui.textEpub)
    val wantHero = !ready || closing
    val alpha by animateFloatAsState(
        targetValue = heroAlphaTarget(wantHero, handedOff),
        animationSpec = if (wantHero) Motion.enter() else Motion.exit(),
        label = "coverHero",
    )
    // Caller-managed, never both ends at once: the source card hides exactly while this hero
    // is the visible claimant, and each same-frame swap is what runs the flight either way.
    val managedVisible = wantHero && !handedOff
    LaunchedEffect(closing, handedOff, alpha) {
        if (closing && !handedOff && alpha >= 1f) onSettledVisible()
    }
    val path = remember(uri) { coverPathFor(uri) }
    val host = LocalCoverTransitionHost.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth(HERO_WIDTH_FRACTION)
                .aspectRatio(HERO_ASPECT)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(host?.coverModifier(path, managedVisible) ?: Modifier)
                .alpha(alpha),
        ) {
            HeroArt(path)
        }
    }
}

/**
 * The hero's art over its panel-tone ground, fading in on arrival exactly like the card cover
 * it flew from. A book with no art keeps the ground: a designed card, never a hole.
 */
@Composable
private fun HeroArt(path: String) {
    val covers = LocalBookCovers.current
    // Width from onSizeChanged rather than BoxWithConstraints, matching the card: constraints
    // would subcompose on every size pass, and this box resizes through the whole flight.
    var widthPx by remember { mutableIntStateOf(0) }
    val art by produceState<ImageBitmap?>(null, path, widthPx) {
        if (widthPx > 0) value = covers.cover(path, path, widthPx)?.asImageBitmap()
    }
    val shown by animateFloatAsState(if (art == null) 0f else 1f, Motion.enter(), label = "heroArt")
    Box(Modifier.fillMaxSize().onSizeChanged { widthPx = it.width }) {
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

/** Fully opaque while covering (open window, close fade-in); gone once handed to the flight. */
private fun heroAlphaTarget(wantHero: Boolean, handedOff: Boolean): Float =
    if (wantHero && !handedOff) 1f else 0f

/** Wide enough to read as the book, narrow enough that the flight visibly travels. */
private const val HERO_WIDTH_FRACTION = 0.72f

/** Comic pages are taller than wide; 2:3 is the common trim — the card's ratio, so no rescale pops. */
private const val HERO_ASPECT = 2f / 3f
