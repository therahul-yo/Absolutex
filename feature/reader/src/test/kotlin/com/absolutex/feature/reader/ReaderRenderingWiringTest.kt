package com.absolutex.feature.reader

import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRenderingWiringTest {
    @Test fun `reader state carries effective enhance values and current flag`() {
        val state = readerRenderingState(
            RenderingPrefs(
                colour = ColourParams(brightness = 0.2f),
                upscaler = Upscaler.LANCZOS,
                enhanceEnabled = true,
            ),
        )

        assertEquals(ColourParams(contrast = 1.05f, vibrance = 0.05f), state.colour)
        assertEquals(Upscaler.MITCHELL, state.upscaler)
        assertTrue(state.enhanceEnabled)
    }

    @Test fun `text epub hides enhance while image readers show it`() {
        assertFalse(readerShowsEnhance(isTextEpub = true))
        assertTrue(readerShowsEnhance(isTextEpub = false))
    }
}
