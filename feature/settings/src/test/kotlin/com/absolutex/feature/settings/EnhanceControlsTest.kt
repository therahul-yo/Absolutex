package com.absolutex.feature.settings

import com.absolutex.core.data.settings.InMemorySettings
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EnhanceControlsTest {
    @Test fun `advanced colour edit turns preset off and clamps the saved values`() = runTest {
        val settings = InMemorySettings()
        val before = RenderingPrefs(upscaler = Upscaler.LANCZOS, enhanceEnabled = true)
        settings.updateRendering { before }
        settings.updateRendering { it.withColour(ColourParams(contrast = 99f)) }
        val after = settings.currentRenderingPrefs()
        assertEquals(before.copy(colour = ColourParams(contrast = ColourParams.CONTRAST_RANGE.endInclusive),
            enhanceEnabled = false), after)
        assertEquals(after.colour, after.effectiveColour)
    }

    @Test fun `advanced upscaler edit turns preset off without rewriting colour`() = runTest {
        val settings = InMemorySettings()
        val before = RenderingPrefs(colour = ColourParams(brightness = 0.1f), enhanceEnabled = true)
        settings.updateRendering { before }
        settings.updateRendering { it.withUpscaler(Upscaler.LANCZOS) }
        assertEquals(before.copy(upscaler = Upscaler.LANCZOS, enhanceEnabled = false), settings.currentRenderingPrefs())
    }

    @Test fun `choosing the already saved advanced value also exits the preset`() {
        val before = RenderingPrefs(enhanceEnabled = true)
        assertFalse(before.withColour(before.colour).enhanceEnabled)
        assertFalse(before.withUpscaler(before.upscaler).enhanceEnabled)
    }
}
