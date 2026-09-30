package com.absolutex.core.data.settings

import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.ColourPipeline
import com.absolutex.core.gpu.Upscaler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceSettingsTest {
    private val custom = RenderingPrefs(
        colour = ColourParams(0.15f, 1.1f, 1.25f, -0.4f, 0.8f, 0.6f, 1.1f, 0.9f, 1f, 1.2f),
        upscaler = Upscaler.LANCZOS, cropEnabled = false, autoBackground = true,
    )

    @Test fun `defaults keep platform sampling and neutral colour without a preset`() {
        val prefs = PrefCodec.decodeRendering(MapPrefBag())
        assertFalse(prefs.enhanceEnabled)
        assertEquals(Upscaler.PLATFORM, prefs.effectiveUpscaler)
        assertTrue(prefs.effectiveColour.isNeutral)
    }

    @Test fun `on and off preserve every custom value and unrelated rendering flags`() = runTest {
        val settings = InMemorySettings()
        settings.updateRendering { custom }
        settings.toggleEnhance()
        val on = settings.currentRenderingPrefs()
        assertEquals(custom.copy(enhanceEnabled = true), on)
        assertEquals(Upscaler.MITCHELL, on.effectiveUpscaler)
        assertEquals(ColourParams(contrast = 1.05f, vibrance = 0.05f), on.effectiveColour)
        settings.toggleEnhance()
        val off = settings.currentRenderingPrefs()
        assertEquals(custom, off)
        assertEquals(custom.upscaler, off.effectiveUpscaler)
        assertEquals(custom.colour, off.effectiveColour)
    }

    @Test fun `crop and background edits do not turn the preset off`() = runTest {
        val settings = InMemorySettings()
        settings.toggleEnhance()
        settings.updateRendering { it.copy(cropEnabled = false, autoBackground = true) }
        assertTrue(settings.currentRenderingPrefs().enhanceEnabled)
        assertFalse(settings.currentRenderingPrefs().cropEnabled)
        assertTrue(settings.currentRenderingPrefs().autoBackground)
    }

    @Test fun `wrongly typed flag defaults off while custom values still clamp safely`() {
        val bag = MapPrefBag(mapOf("enhance_enabled" to "yes", "colour_contrast" to 9f))
        val prefs = PrefCodec.decodeRendering(bag)
        assertFalse(prefs.enhanceEnabled)
        assertEquals(ColourParams.CONTRAST_RANGE.endInclusive, prefs.colour.contrast)
        val enabled = PrefCodec.decodeRendering(MapPrefBag(mapOf("enhance_enabled" to true, "colour_gamma" to -10f)))
        assertTrue(enabled.enhanceEnabled)
        assertEquals(ColourParams.GAMMA_RANGE.start, enabled.colour.gamma)
        assertEquals(ColourParams(contrast = 1.05f, vibrance = 0.05f), enabled.effectiveColour)
    }

    @Test fun `preset respects gesture and one-to-one kernel gates`() {
        val pipeline = ColourPipeline()
        val on = RenderingPrefs(enhanceEnabled = true)
        assertTrue(pipeline.shouldShade(on.effectiveColour, on.effectiveUpscaler, atRest = false))
        assertEquals(Upscaler.PLATFORM, pipeline.shadeMode(on.effectiveUpscaler, atRest = false, magnifying = true))
        assertEquals(Upscaler.PLATFORM, pipeline.shadeMode(on.effectiveUpscaler, atRest = true, magnifying = false))
        assertEquals(Upscaler.MITCHELL, pipeline.shadeMode(on.effectiveUpscaler, atRest = true, magnifying = true))
        val off = RenderingPrefs()
        assertFalse(pipeline.shouldShade(off.effectiveColour, off.effectiveUpscaler, atRest = true))
    }
}
