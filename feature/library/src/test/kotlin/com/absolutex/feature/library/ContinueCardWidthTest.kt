package com.absolutex.feature.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wheel's cover width: full size where the side covers fit inside the gutter, less where not. */
class ContinueCardWidthTest {

    @Test fun `a roomy screen keeps the full width`() {
        assertEquals(132.dp, continueCardWidth(411.dp))
        assertEquals(132.dp, continueCardWidth(840.dp))
    }

    @Test fun `a narrow phone gets a narrower cover than the full width`() {
        val width = continueCardWidth(354.dp)
        assertTrue("$width", width < 132.dp)
        assertTrue("$width", width > 100.dp)
    }

    @Test fun `the side covers rest inside the gutter`() {
        listOf(320.dp, 354.dp, 360.dp, 393.dp, 411.dp).forEach { screen ->
            val width = continueCardWidth(screen).value
            // Where the neighbour's outer edge lands at rest: its slot, less its shrink, plus its pull.
            val edge = screen.value / 2 - 1.5f * width - 4f + 0.08f * width + 14f
            assertTrue("$screen: edge $edge", edge >= Space.Edge.value - 0.01f)
        }
    }

    @Test fun `a screen too small for any cover never goes negative`() {
        assertEquals(0.dp, continueCardWidth(0.dp))
    }
}
