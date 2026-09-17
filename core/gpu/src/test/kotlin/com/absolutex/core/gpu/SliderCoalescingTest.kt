package com.absolutex.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the slider-write coalescing the #23 review (item 1) requires: one write per gesture, not
 * one per frame. [coalesceSlider] is the helper [ColourPanel] uses to collapse a ~120 fps drag
 * stream to a single commit on release — so the DataStore never sees a backlog and the thumb
 * never lags the pointer (§4 live preview at 120 fps while dragging).
 */
class SliderCoalescingTest {

    @Test
    fun `a drag commits once at the end, not once per frame`() {
        // 40 Change events at ~120 fps during a drag, then exactly one Finish on release — the
        // stream ColourPanel feeds to its store. Coalescing must collapse this to a single
        // commit carrying the last dragged value.
        val events = List(40) { SliderEvent.Change(it / 40f) } + SliderEvent.Finish
        val commits = coalesceSlider(events)
        assertEquals(1, commits.size)
        assertEquals(39 / 40f, commits.single(), 1e-6f)
    }

    @Test
    fun `a bare finish with no changes commits nothing`() {
        // No Change before Finish — nothing was pending, so nothing commits. The thumb only
        // reports an actual change to the store.
        assertEquals(emptyList<Float>(), coalesceSlider(listOf(SliderEvent.Finish)))
    }

    @Test
    fun `a finish commits whatever the last change was, not the first`() {
        val events = listOf(
            SliderEvent.Change(0.1f),
            SliderEvent.Change(0.2f),
            SliderEvent.Change(0.9f),
            SliderEvent.Finish,
        )
        assertEquals(listOf(0.9f), coalesceSlider(events))
    }

    @Test
    fun `the live preview tracks every change even though only the finish commits`() {
        // Verifies the preview invariant: every Change updates the local value, so the page
        // repaints at the dragged value even before the store is written. The last Change before
        // a Finish is what gets committed.
        val events = listOf(
            SliderEvent.Change(0.1f),
            SliderEvent.Change(0.5f),
            SliderEvent.Change(0.9f),
            SliderEvent.Finish,
            // A second, separate gesture — commits its own final value.
            SliderEvent.Change(0.2f),
            SliderEvent.Change(0.3f),
            SliderEvent.Finish,
        )
        val commits = coalesceSlider(events)
        assertEquals(2, commits.size)
        assertEquals(listOf(0.9f, 0.3f), commits)
    }

    @Test
    fun `interleaved empty finishes do not emit spurious commits`() {
        val events = listOf(
            SliderEvent.Finish,        // nothing pending yet
            SliderEvent.Change(0.4f),
            SliderEvent.Finish,        // commits 0.4
            SliderEvent.Finish,        // nothing new pending
        )
        assertEquals(listOf(0.4f), coalesceSlider(events))
    }
}
