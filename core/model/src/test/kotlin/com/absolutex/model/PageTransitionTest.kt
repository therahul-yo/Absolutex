package com.absolutex.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransitionTest {

    @Test fun `slide leaves every page to the pager`() {
        listOf(-1f, -0.5f, 0f, 0.5f, 1f).forEach { offset ->
            assertEquals(TransitionLayer(0f, 1f), transitionLayerFor(PageTransition.SLIDE, offset))
        }
    }

    @Test fun `page over pins the page arriving and leaves the one leaving alone`() {
        assertEquals(TransitionLayer(-0.5f, 1f), transitionLayerFor(PageTransition.PAGE_OVER, 0.5f))
        assertEquals(TransitionLayer(0f, 1f), transitionLayerFor(PageTransition.PAGE_OVER, -0.5f))
    }

    @Test fun `reveal pins the page leaving and fades it out`() {
        assertEquals(TransitionLayer(0.5f, 0.5f), transitionLayerFor(PageTransition.REVEAL, -0.5f))
        assertEquals(TransitionLayer(0f, 1f), transitionLayerFor(PageTransition.REVEAL, 0.5f))
    }

    @Test fun `the page on screen is never moved or faded by any transition`() {
        PageTransition.entries.forEach { transition ->
            assertEquals("$transition", TransitionLayer(0f, 1f), transitionLayerFor(transition, 0f))
        }
    }

    @Test fun `a page fully gone is invisible under reveal, never negative`() {
        assertEquals(TransitionLayer(1f, 0f), transitionLayerFor(PageTransition.REVEAL, -1f))
        assertEquals(0f, transitionLayerFor(PageTransition.REVEAL, -1.5f).alpha, 0f)
    }
}
