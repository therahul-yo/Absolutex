package com.absolutex.model

/** How one page gives way to the next (§5.2 transitions). */
enum class PageTransition {
    /** Both pages move with the finger: the pager's own behaviour, and the plainest. */
    SLIDE,

    /** The page being read stays put while the next slides in over it. */
    PAGE_OVER,

    /** The next page is already there: the page being read slides away and fades off it. */
    REVEAL,
}

/**
 * What one page's layer does at a given scroll position, as fractions of the page's own size.
 *
 * Pure, because the alternative is trigonometry inside a draw lambda that runs 120 times a second
 * and cannot be tested. [translationFraction] is added to what the pager already does: -offset
 * pins a page exactly where it is, which is what both non-sliding transitions are built from.
 */
data class TransitionLayer(val translationFraction: Float, val alpha: Float)

/**
 * [offset] is the page's position relative to the viewport: 0 while it fills the screen, -1 once it
 * has left to the start, +1 while it waits at the end.
 */
fun transitionLayerFor(transition: PageTransition, offset: Float): TransitionLayer = when {
    transition == PageTransition.SLIDE -> TransitionLayer(0f, 1f)
    // Only the page arriving is pinned; the one leaving still slides out from under it.
    transition == PageTransition.PAGE_OVER && offset > 0f -> TransitionLayer(-offset, 1f)
    // The mirror image: the page leaving moves, fading, over the one already in place.
    transition == PageTransition.REVEAL && offset < 0f ->
        TransitionLayer(-offset, (1f + offset).coerceIn(0f, 1f))
    else -> TransitionLayer(0f, 1f)
}
