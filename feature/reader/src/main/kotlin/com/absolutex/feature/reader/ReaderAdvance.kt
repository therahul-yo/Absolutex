package com.absolutex.feature.reader

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import com.absolutex.core.data.settings.ReaderPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The pager's forward-turn step (§5.2 auto-advance).
 *
 * A forward attempt already on the last screen fires [onFinished] once for that attempt, instead
 * of animating a turn that would land on the same screen it started on. This is the one place
 * "turned past the last page" is decided, so tap zones, keys and edge swipes all agree — every one
 * of them calls through the step this returns.
 */
internal fun pagerStep(
    pagerState: PagerState,
    lastScreen: Int,
    prefs: ReaderPrefs,
    scope: CoroutineScope,
    onFinished: (() -> Unit)?,
): (Int) -> Unit = { step ->
    if (step > 0 && pagerState.currentPage == lastScreen) {
        onFinished?.invoke()
    } else {
        scope.launch { pagerState.turn(step, lastScreen, prefs) }
    }
}

/**
 * The strip's forward-scroll step (§5.2 auto-advance): the same "fire once, never animate
 * nowhere" rule as [pagerStep], for a scrolling list instead of a pager.
 */
internal fun stripStep(
    listState: LazyListState,
    prefs: ReaderPrefs,
    scope: CoroutineScope,
    onFinished: (() -> Unit)?,
): (Int) -> Unit = { direction ->
    if (direction > 0 && !listState.canScrollForward) {
        onFinished?.invoke()
    } else {
        scope.launch {
            val info = listState.layoutInfo
            val extent = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            listState.animateScrollBy(
                direction * extent * prefs.scrollStepPercent / PERCENT,
                animationSpec = tween(prefs.pageTurnMs),
            )
        }
    }
}
