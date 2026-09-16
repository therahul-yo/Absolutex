package com.absolutex.feature.reader

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.model.PageTransition
import com.absolutex.model.ReadingFlow
import com.absolutex.model.transitionLayerFor

/** Moves the pager by whole screens, at the turn speed §5.2's animation tuning asks for. */
internal suspend fun PagerState.turn(step: Int, lastScreen: Int, prefs: ReaderPrefs) =
    animateScrollToPage(
        page = (currentPage + step).coerceIn(0, lastScreen),
        animationSpec = tween(prefs.pageTurnMs),
    )

/**
 * A page's transition layer (§5.2). getOffsetDistanceInPages is the page's position in viewports:
 * 0 while it fills the screen, +1 waiting at the end, -1 once it has left. Read in the
 * graphicsLayer lambda, which is the draw phase:
 * the pager's offset changes every frame of a turn, and reading it in a composable body instead
 * would recompose the page — and its whole subtree — 120 times a second.
 */
internal fun Modifier.transition(
    pagerState: PagerState,
    index: Int,
    transition: PageTransition,
    vertical: Boolean,
): Modifier = if (transition == PageTransition.SLIDE) {
    this
} else {
    graphicsLayer {
        val layer = transitionLayerFor(transition, pagerState.getOffsetDistanceInPages(index))
        if (vertical) {
            translationY = layer.translationFraction * size.height
        } else {
            translationX = layer.translationFraction * size.width
        }
        alpha = layer.alpha
    }
}

/** The pager itself: same page slot either way, only the axis and direction change. */
@Composable
internal fun ReaderPager(
    flow: ReadingFlow,
    pagerState: PagerState,
    scrollable: Boolean,
    page: @Composable PagerScope.(Int) -> Unit,
) {
    // TODO(phase3): beyondViewportPageCount should follow scroll velocity and the
    // prefetch depth setting (§3). Fixed at 1 for the Phase 2 slice.
    if (flow == ReadingFlow.VERTICAL) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scrollable,
            beyondViewportPageCount = 1,
            pageContent = page,
        )
    } else {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scrollable,
            beyondViewportPageCount = 1,
            // A manga reads right to left whatever language the phone is in. The pager already
            // mirrors under an RTL locale, so reverse only when the book and the UI disagree —
            // otherwise an Arabic-locale phone would read every Western comic backwards.
            reverseLayout = (flow == ReadingFlow.RTL) != (LocalLayoutDirection.current == LayoutDirection.Rtl),
            pageContent = page,
        )
    }
}
