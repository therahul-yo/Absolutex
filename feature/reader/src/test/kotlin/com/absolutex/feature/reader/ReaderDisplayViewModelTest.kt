package com.absolutex.feature.reader

import com.absolutex.core.data.settings.InMemorySettings
import com.absolutex.core.data.settings.withColour
import com.absolutex.core.data.settings.withUpscaler
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDisplayViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `sheet edits reach Settings source and draw state before gesture release`() = runTest(main.dispatcher) {
        val settings = InMemorySettings()
        settings.updateRendering { it.copy(enhanceEnabled = true, upscaler = Upscaler.LANCZOS) }
        val vm = ReaderDisplayViewModel(settings, settings)
        advanceUntilIdle()
        for (contrast in listOf(1.1f, 1.2f, 1.3f)) {
            vm.setColour(ColourParams(contrast = contrast))
            advanceUntilIdle()
            val persisted = settings.currentRenderingPrefs()
            assertFalse(persisted.enhanceEnabled)
            assertEquals(Upscaler.LANCZOS, persisted.upscaler)
            assertEquals(persisted, vm.prefs.value)
            assertEquals(contrast, readerRenderingState(vm.prefs.value).colour.contrast)
        }
    }

    @Test fun `Settings edits and Enhance toggles update the sheet from the same store`() = runTest(main.dispatcher) {
        val settings = InMemorySettings()
        val vm = ReaderDisplayViewModel(settings, settings)
        advanceUntilIdle()
        settings.updateRendering { it.withColour(ColourParams(gammaR = 1.3f)).withUpscaler(Upscaler.LANCZOS) }
        advanceUntilIdle()
        assertEquals(settings.currentRenderingPrefs(), vm.prefs.value)
        vm.toggle()
        advanceUntilIdle()
        assertEquals(true, vm.prefs.value.enhanceEnabled)
        vm.setUpscaler(Upscaler.PLATFORM)
        advanceUntilIdle()
        assertFalse(settings.currentRenderingPrefs().enhanceEnabled)
        assertEquals(1.3f, settings.currentRenderingPrefs().colour.gammaR)
        vm.setColour(ColourParams.NEUTRAL)
        advanceUntilIdle()
        assertEquals(ColourParams.NEUTRAL, settings.currentRenderingPrefs().colour)
    }

    @Test fun `Display panel reserves more of the page than normal chrome`() {
        assertEquals(0.55f, readerChromeHeightFraction(display = true))
        assertEquals(CHROME_MAX_HEIGHT, readerChromeHeightFraction(display = false))
    }
}
