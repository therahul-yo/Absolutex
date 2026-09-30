package com.absolutex.core.data.backup

import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.data.settings.toggleEnhance
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnhanceBackupTest : BackupFixture() {
    @Test fun `on and off backups retain custom values rather than serializing the overlay`() = runTest {
        val custom = RenderingPrefs(colour = ColourParams(contrast = 1.3f, gammaB = 0.9f), upscaler = Upscaler.LANCZOS)
        for (on in listOf(false, true)) {
            val expected = custom.copy(enhanceEnabled = on)
            settings.updateRendering { expected }
            val bytes = repository.export("test").bytes
            val preferences = BackupCodec.read(bytes.inputStream()).preferences
            assertEquals(on, preferences["enhance_enabled"])
            assertEquals("LANCZOS", preferences["upscaler"])
            assertEquals(1.3f, preferences["colour_contrast"])
            settings.updateRendering { RenderingPrefs() }
            repository.restore(bytes.inputStream())
            assertEquals(expected, settings.currentRenderingPrefs())
        }
        settings.toggleEnhance()
        assertEquals(custom, settings.currentRenderingPrefs())
    }

    @Test fun `imported custom values clamp safely underneath the preset`() = runTest {
        repository.restore(
            """{"schemaVersion":1,"appVersion":"test","preferences":{"enhance_enabled":true,
                "upscaler":"FUTURE","colour_contrast":999,"colour_vibrance":-999}}""".byteInputStream(),
        )
        val on = settings.currentRenderingPrefs()
        assertTrue(on.enhanceEnabled)
        assertEquals(Upscaler.PLATFORM, on.upscaler)
        assertEquals(ColourParams.CONTRAST_RANGE.endInclusive, on.colour.contrast)
        assertEquals(ColourParams.VIBRANCE_RANGE.start, on.colour.vibrance)
        assertEquals(ColourParams(contrast = 1.05f, vibrance = 0.05f), on.effectiveColour)
        settings.toggleEnhance()
        val off = settings.currentRenderingPrefs()
        assertFalse(off.enhanceEnabled)
        assertEquals(on.colour, off.effectiveColour)
    }

    @Test fun `old backup omission preserves existing flag and defaults off on a new store`() = runTest {
        val bytes = """{"schemaVersion":1,"appVersion":"old","preferences":{"true_black":false}}"""
        repository.restore(bytes.byteInputStream())
        assertFalse(settings.currentRenderingPrefs().enhanceEnabled)
        settings.toggleEnhance()
        repository.restore(bytes.byteInputStream())
        assertTrue(settings.currentRenderingPrefs().enhanceEnabled)
    }
}
