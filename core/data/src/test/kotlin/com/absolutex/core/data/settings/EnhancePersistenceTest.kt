package com.absolutex.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnhancePersistenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val custom = RenderingPrefs(colour = ColourParams(contrast = 1.3f), upscaler = Upscaler.LANCZOS)

    private fun open(file: File): Pair<DataStoreSettings, Job> {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        return DataStoreSettings(store) to job
    }

    @Test fun `preset and custom values persist and off restores custom values after reopen`() = runBlocking {
        val file = File(tmp.root, "enhance.preferences_pb")
        val (first, firstJob) = open(file)
        first.updateRendering { custom }
        first.toggleEnhance()
        firstJob.cancelAndJoin()
        val (second, secondJob) = open(file)
        assertEquals(custom.copy(enhanceEnabled = true), second.currentRenderingPrefs())
        second.toggleEnhance()
        assertEquals(custom, second.currentRenderingPrefs())
        secondJob.cancelAndJoin()
        val (third, thirdJob) = open(file)
        assertEquals(custom, third.currentRenderingPrefs())
        assertEquals(custom.colour, third.currentRenderingPrefs().effectiveColour)
        assertEquals(custom.upscaler, third.currentRenderingPrefs().effectiveUpscaler)
        thirdJob.cancelAndJoin()
    }

    @Test fun `rapid concurrent toggles lose no taps and preserve custom values`() = runBlocking {
        val (settings, job) = open(File(tmp.root, "rapid.preferences_pb"))
        settings.updateRendering { custom }
        coroutineScope { repeat(100) { launch(Dispatchers.Default) { settings.toggleEnhance() } } }
        assertFalse(settings.currentRenderingPrefs().enhanceEnabled)
        coroutineScope { repeat(101) { launch(Dispatchers.Default) { settings.toggleEnhance() } } }
        assertTrue(settings.currentRenderingPrefs().enhanceEnabled)
        assertEquals(custom.copy(enhanceEnabled = true), settings.currentRenderingPrefs())
        job.cancelAndJoin()
    }

    @Test fun `one advanced edit persists both the custom change and disabled preset`() = runBlocking {
        val file = File(tmp.root, "advanced.preferences_pb")
        val (settings, job) = open(file)
        settings.updateRendering { custom.copy(enhanceEnabled = true) }
        settings.updateRendering { it.copy(enhanceEnabled = false, upscaler = Upscaler.PLATFORM) }
        val expected = custom.copy(upscaler = Upscaler.PLATFORM)
        assertEquals(expected, settings.currentRenderingPrefs())
        job.cancelAndJoin()
        val (reopened, reopenedJob) = open(file)
        assertEquals(expected, reopened.currentRenderingPrefs())
        reopenedJob.cancelAndJoin()
    }
}
