package com.absolutex.feature.reader

import com.absolutex.model.TapZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadSideTest {

    // A 1200 × 900 screen: each half of a spread is 600 wide; grid columns are 400 wide.
    private val half = 600
    private val height = 900

    @Test fun `a tap on either half lands in the whole screen's grid`() {
        assertEquals(TapZone.MIDDLE_LEFT, SpreadSide.LEFT.zoneAt(100f, 450f, half, height, mirrored = false))
        assertEquals(TapZone.CENTER, SpreadSide.LEFT.zoneAt(500f, 450f, half, height, mirrored = false))
        assertEquals(TapZone.CENTER, SpreadSide.RIGHT.zoneAt(100f, 450f, half, height, mirrored = false))
        assertEquals(TapZone.MIDDLE_RIGHT, SpreadSide.RIGHT.zoneAt(500f, 450f, half, height, mirrored = false))
    }

    @Test fun `a single page keeps its own grid`() {
        assertEquals(TapZone.MIDDLE_LEFT, SpreadSide.NONE.zoneAt(100f, 450f, 1200, height, mirrored = false))
        assertEquals(TapZone.MIDDLE_RIGHT, SpreadSide.NONE.zoneAt(1100f, 450f, 1200, height, mirrored = false))
    }

    @Test fun `mirroring flips the whole screen, not each half`() {
        assertEquals(TapZone.MIDDLE_RIGHT, SpreadSide.LEFT.zoneAt(100f, 450f, half, height, mirrored = true))
        assertEquals(TapZone.MIDDLE_LEFT, SpreadSide.RIGHT.zoneAt(500f, 450f, half, height, mirrored = true))
    }
}
