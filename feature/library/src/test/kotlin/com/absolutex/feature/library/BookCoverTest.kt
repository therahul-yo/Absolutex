package com.absolutex.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCoverTest {
    @Test fun `placeholder caption waits for a tile wide enough for two lines`() {
        assertFalse(placeholderCaptionVisible(TITLE_MIN_WIDTH_PX - 1))
        assertTrue(placeholderCaptionVisible(TITLE_MIN_WIDTH_PX))
        assertEquals(2, PLACEHOLDER_CAPTION_MAX_LINES)
    }
}
