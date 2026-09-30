package com.absolutex.feature.reader

import com.absolutex.core.data.settings.InMemorySettings
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnhanceViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `button taps toggle the store value rather than the last displayed value`() = runTest(main.dispatcher) {
        val settings = InMemorySettings()
        val custom = RenderingPrefs(colour = ColourParams(brightness = 0.2f), upscaler = Upscaler.LANCZOS)
        settings.updateRendering { custom }
        val vm = EnhanceViewModel(settings, settings)
        advanceUntilIdle()
        repeat(3) { vm.toggle() }
        advanceUntilIdle()
        assertEquals(true, vm.enabled.first())
        assertEquals(custom.copy(enhanceEnabled = true), settings.currentRenderingPrefs())
        vm.toggle()
        advanceUntilIdle()
        assertEquals(false, vm.enabled.first())
        assertEquals(custom, settings.currentRenderingPrefs())
    }

    @Test fun `control observes advanced edits and persisted flag changes`() = runTest(main.dispatcher) {
        val settings = InMemorySettings()
        settings.updateRendering { it.copy(enhanceEnabled = true) }
        val vm = EnhanceViewModel(settings, settings)
        advanceUntilIdle()
        assertEquals(true, vm.enabled.first())
        settings.updateRendering { it.copy(enhanceEnabled = false, upscaler = Upscaler.LANCZOS) }
        advanceUntilIdle()
        assertEquals(false, vm.enabled.first())
    }

    @Test fun `state flow retains the current flag for a recreated button`() = runTest(main.dispatcher) {
        val settings = InMemorySettings()
        settings.updateRendering { it.copy(enhanceEnabled = true) }
        val vm = EnhanceViewModel(settings, settings)
        advanceUntilIdle()
        assertEquals(true, vm.enabled.value)
    }
}
