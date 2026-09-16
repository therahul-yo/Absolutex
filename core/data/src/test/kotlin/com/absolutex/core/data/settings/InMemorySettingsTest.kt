package com.absolutex.core.data.settings

import com.absolutex.core.gpu.Upscaler
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemorySettingsTest {

    @Test
    fun `starts from the defaults when the store is empty`() = runTest {
        val settings = InMemorySettings()
        assertEquals(AppPrefs(), settings.appPrefs.first())
        assertEquals(ReaderPrefs(), settings.readerPrefs.first())
    }

    @Test
    fun `starts from what the store already holds`() = runTest {
        val bag = MapPrefBag()
        PrefCodec.encodeReader(ReaderPrefs(readingFlow = ReadingFlow.RTL), bag)
        val settings = InMemorySettings(bag)
        assertEquals(ReadingFlow.RTL, settings.readerPrefs.first().readingFlow)
    }

    @Test
    fun `an update is visible to a reader collecting the flow`() = runTest {
        val settings = InMemorySettings()
        settings.updateReader { it.copy(readingFlow = ReadingFlow.RTL, fitMode = FitMode.FIT_WIDTH) }
        val prefs = settings.readerPrefs.first()
        assertEquals(ReadingFlow.RTL, prefs.readingFlow)
        assertEquals(FitMode.FIT_WIDTH, prefs.fitMode)
    }

    @Test
    fun `an update is persisted to the backing store, not just held in memory`() = runTest {
        val bag = MapPrefBag()
        InMemorySettings(bag).updateApp { it.copy(trueBlack = true, nightMode = NightMode.ON) }
        // A fresh instance over the same bag is what a process restart looks like.
        val reopened = InMemorySettings(bag)
        assertTrue(reopened.appPrefs.first().trueBlack)
        assertEquals(NightMode.ON, reopened.appPrefs.first().nightMode)
    }

    @Test
    fun `an update reports back the clamped value, not the requested one`() = runTest {
        val settings = InMemorySettings()
        settings.updateApp { it.copy(cacheSizeMiB = Int.MAX_VALUE) }
        val stored = settings.appPrefs.first().cacheSizeMiB
        assertEquals(AppPrefs.MAX_CACHE_MIB, stored)
        assertNotEquals(Int.MAX_VALUE, stored)
    }

    @Test
    fun `updating app prefs leaves reader prefs alone`() = runTest {
        val settings = InMemorySettings()
        settings.updateReader { it.copy(fitMode = FitMode.FULL_SIZE) }
        settings.updateApp { it.copy(dynamicColour = false) }
        assertEquals(FitMode.FULL_SIZE, settings.readerPrefs.first().fitMode)
        assertFalse(settings.appPrefs.first().dynamicColour)
    }

    @Test
    fun `the current-value accessors agree with the flows`() = runTest {
        val settings = InMemorySettings()
        settings.updateApp { it.copy(showHiddenFolders = true) }
        settings.updateReader { it.copy(readingFlow = ReadingFlow.VERTICAL) }
        assertEquals(settings.appPrefs.first(), settings.currentAppPrefs())
        assertEquals(settings.readerPrefs.first(), settings.currentReaderPrefs())
    }

    @Test
    fun `a corrupt store opens on defaults rather than throwing`() = runTest {
        val bag = MapPrefBag(
            mapOf(
                PrefCodec.KEY_NIGHT_MODE to 7,
                PrefCodec.KEY_READING_FLOW to "SIDEWAYS",
                PrefCodec.KEY_CACHE_MIB to "not a number",
            )
        )
        val settings = InMemorySettings(bag)
        assertEquals(AppPrefs(), settings.appPrefs.first())
        assertEquals(ReaderPrefs(), settings.readerPrefs.first())
    }

    @Test
    fun `an update over a corrupt store repairs the broken keys`() = runTest {
        val bag = MapPrefBag(mapOf(PrefCodec.KEY_NIGHT_MODE to 7))
        val settings = InMemorySettings(bag)
        settings.updateApp { it.copy(trueBlack = true) }
        assertEquals("NightMode.SYSTEM", "SYSTEM", bag.snapshot()[PrefCodec.KEY_NIGHT_MODE])
        assertEquals(NightMode.SYSTEM, InMemorySettings(bag).appPrefs.first().nightMode)
    }

    @Test
    fun `rendering starts neutral and an update is visible to collectors`() = runTest {
        val settings = InMemorySettings()
        assertEquals(RenderingPrefs(), settings.renderingPrefs.first())
        settings.updateRendering { it.copy(colour = it.colour.copy(brightness = 0.2f)) }
        assertEquals(0.2f, settings.renderingPrefs.first().colour.brightness)
    }

    @Test
    fun `a rendering update is persisted to the backing store`() = runTest {
        val bag = MapPrefBag()
        InMemorySettings(bag).updateRendering { it.copy(colour = it.colour.copy(temperature = 0.4f)) }
        // A fresh instance over the same bag is what a process restart looks like.
        assertEquals(0.4f, InMemorySettings(bag).renderingPrefs.first().colour.temperature)
    }

    @Test
    fun `updating rendering leaves app and reader prefs alone`() = runTest {
        val settings = InMemorySettings()
        settings.updateRendering { it.copy(colour = it.colour.copy(vibrance = 0.7f)) }
        assertEquals(AppPrefs(), settings.appPrefs.first())
        assertEquals(ReaderPrefs(), settings.readerPrefs.first())
    }

    @Test
    fun `an upscaler update persists and leaves colour alone`() = runTest {
        val bag = MapPrefBag()
        val settings = InMemorySettings(bag)
        settings.updateRendering { it.copy(upscaler = Upscaler.LANCZOS) }
        assertEquals(Upscaler.LANCZOS, settings.renderingPrefs.first().upscaler)
        assertEquals(RenderingPrefs().colour, settings.renderingPrefs.first().colour)
        // A fresh instance over the same bag is what a process restart looks like.
        val reopened = InMemorySettings(bag)
        assertEquals(Upscaler.LANCZOS, reopened.renderingPrefs.first().upscaler)
        assertEquals(RenderingPrefs().colour, reopened.renderingPrefs.first().colour)
    }

    @Test
    fun `a crop toggle persists and leaves colour and upscaler alone`() = runTest {
        val bag = MapPrefBag()
        val settings = InMemorySettings(bag)
        settings.updateRendering { it.copy(cropEnabled = false) }
        assertEquals(false, settings.renderingPrefs.first().cropEnabled)
        assertEquals(RenderingPrefs().colour, settings.renderingPrefs.first().colour)
        assertEquals(RenderingPrefs().upscaler, settings.renderingPrefs.first().upscaler)
        // A fresh instance over the same bag is what a process restart looks like.
        val reopened = InMemorySettings(bag)
        assertEquals(false, reopened.renderingPrefs.first().cropEnabled)
        assertEquals(RenderingPrefs().colour, reopened.renderingPrefs.first().colour)
        assertEquals(RenderingPrefs().upscaler, reopened.renderingPrefs.first().upscaler)
    }
}
