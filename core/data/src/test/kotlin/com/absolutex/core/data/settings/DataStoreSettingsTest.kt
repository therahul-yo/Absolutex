package com.absolutex.core.data.settings

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A store on [file] whose scope the test can cancel, since one file allows one live store. */
    private fun open(file: File): Pair<DataStoreSettings, Job> {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        return DataStoreSettings(store) to job
    }

    private fun file(name: String = "settings.preferences_pb") = File(tmp.root, name)

    /** Writes raw keys the way an older build might have, then closes the store. */
    private suspend fun seed(file: File, block: (MutablePreferences) -> Unit) {
        val job = Job()
        val raw = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        raw.edit(block)
        job.cancelAndJoin()
    }

    @Test fun `an empty store reads as the contract defaults`() = runBlocking {
        val (settings, job) = open(file())
        assertEquals(AppPrefs(), settings.currentAppPrefs())
        assertEquals(ReaderPrefs(), settings.currentReaderPrefs())
        job.cancelAndJoin()
    }

    @Test fun `writes survive reopening the store`() = runBlocking {
        val f = file()
        val (first, firstJob) = open(f)
        first.updateReader { it.copy(readingFlow = ReadingFlow.RTL, fitMode = FitMode.FIT_WIDTH) }
        first.updateApp { it.copy(trueBlack = true, cacheSizeMiB = 1024) }
        firstJob.cancelAndJoin()

        val (reopened, job) = open(f)
        assertEquals(ReaderPrefs(ReadingFlow.RTL, FitMode.FIT_WIDTH), reopened.currentReaderPrefs())
        assertEquals(AppPrefs(trueBlack = true, cacheSizeMiB = 1024), reopened.currentAppPrefs())
        job.cancelAndJoin()
    }

    @Test fun `a reader write leaves app prefs alone and the reverse`() = runBlocking {
        val (settings, job) = open(file())
        settings.updateApp { it.copy(nightMode = NightMode.ON) }
        settings.updateReader { it.copy(readingFlow = ReadingFlow.VERTICAL) }
        assertEquals(NightMode.ON, settings.currentAppPrefs().nightMode)
        assertEquals(ReadingFlow.VERTICAL, settings.currentReaderPrefs().readingFlow)
        job.cancelAndJoin()
    }

    @Test fun `a wrongly typed entry falls back for that field only, and a write repairs it`() = runBlocking {
        val f = file()
        // An older build stored reading_flow as an int. DataStore's own get() would throw here.
        seed(f) {
            it[intPreferencesKey(PrefCodec.KEY_READING_FLOW)] = 7
            it[stringPreferencesKey(PrefCodec.KEY_FIT_MODE)] = FitMode.FULL_SIZE.name
        }

        val (settings, settingsJob) = open(f)
        assertEquals(ReaderPrefs(fitMode = FitMode.FULL_SIZE), settings.currentReaderPrefs())
        settings.updateReader { it.copy(readingFlow = ReadingFlow.RTL) }
        assertEquals(ReaderPrefs(ReadingFlow.RTL, FitMode.FULL_SIZE), settings.currentReaderPrefs())
        settingsJob.cancelAndJoin()
    }

    @Test fun `a corrupt file resets to defaults instead of crashing`() = runBlocking {
        val f = file().apply { writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37)) }
        val (settings, job) = open(f)
        assertEquals(AppPrefs(), settings.currentAppPrefs())
        job.cancelAndJoin()
    }

    @Test fun `every app field round-trips and survives reopen`() = runBlocking {
        val f = file()
        val expected = AppPrefs(
            nightMode = NightMode.ON,
            dynamicColour = false,
            trueBlack = true,
            cacheSizeMiB = 1024,
            showHiddenFolders = true,
            openGenericArchives = true,
            openImageFolders = true,
        )
        val (first, firstJob) = open(f)
        first.updateApp { expected }
        assertEquals(expected, first.currentAppPrefs())
        firstJob.cancelAndJoin()

        val (reopened, job) = open(f)
        assertEquals(expected, reopened.currentAppPrefs())
        job.cancelAndJoin()
    }

    @Test fun `every reading-flow and fit-mode combination round-trips`() = runBlocking {
        val (settings, job) = open(file())
        for (flow in ReadingFlow.entries) {
            for (fit in FitMode.entries) {
                settings.updateReader { it.copy(readingFlow = flow, fitMode = fit) }
                assertEquals(ReaderPrefs(flow, fit), settings.currentReaderPrefs())
            }
        }
        job.cancelAndJoin()
    }

    @Test fun `an out-of-range cache written raw restarts clamped to the bounds`() = runBlocking {
        val hi = file("hi.preferences_pb")
        seed(hi) { it[intPreferencesKey(PrefCodec.KEY_CACHE_MIB)] = AppPrefs.MAX_CACHE_MIB + 1 }

        val (hiSettings, hiSettingsJob) = open(hi)
        assertEquals(AppPrefs.MAX_CACHE_MIB, hiSettings.currentAppPrefs().cacheSizeMiB)
        hiSettingsJob.cancelAndJoin()

        // Reopening keeps the clamped read stable rather than drifting back to the default.
        val (reopened, reopenedJob) = open(hi)
        assertEquals(AppPrefs.MAX_CACHE_MIB, reopened.currentAppPrefs().cacheSizeMiB)
        reopenedJob.cancelAndJoin()

        val lo = file("lo.preferences_pb")
        seed(lo) { it[intPreferencesKey(PrefCodec.KEY_CACHE_MIB)] = AppPrefs.MIN_CACHE_MIB - 1 }

        val (loSettings, loSettingsJob) = open(lo)
        assertEquals(AppPrefs.MIN_CACHE_MIB, loSettings.currentAppPrefs().cacheSizeMiB)
        loSettingsJob.cancelAndJoin()
    }

    @Test fun `unrecognised enum names fall back to the defaults`() = runBlocking {
        val f = file()
        seed(f) {
            it[stringPreferencesKey(PrefCodec.KEY_NIGHT_MODE)] = "MIDNIGHT"
            it[stringPreferencesKey(PrefCodec.KEY_READING_FLOW)] = "SIDEWAYS"
        }

        val (settings, settingsJob) = open(f)
        assertEquals(AppPrefs().nightMode, settings.currentAppPrefs().nightMode)
        assertEquals(ReadingFlow.LTR, settings.currentReaderPrefs().readingFlow)
        settingsJob.cancelAndJoin()
    }

    @Test fun `a partial store migrates present keys and defaults the rest`() = runBlocking {
        val f = file()
        // A first run after a new key ships: only true_black exists, everything else is absent.
        seed(f) { it[booleanPreferencesKey(PrefCodec.KEY_TRUE_BLACK)] = true }

        val (settings, settingsJob) = open(f)
        // Present keys survive; absent keys come from AppPrefs()/ReaderPrefs() defaults, never copies here.
        assertEquals(AppPrefs(trueBlack = true), settings.currentAppPrefs())
        assertEquals(ReaderPrefs(), settings.currentReaderPrefs())
        settingsJob.cancelAndJoin()
    }

    @Test fun `concurrent app and reader updates lose neither write`() = runBlocking {
        val (settings, job) = open(file())
        coroutineScope {
            val app = async { settings.updateApp { it.copy(nightMode = NightMode.ON, trueBlack = true) } }
            val reader = async { settings.updateReader { it.copy(readingFlow = ReadingFlow.RTL) } }
            app.await()
            reader.await()
        }
        assertEquals(AppPrefs(nightMode = NightMode.ON, trueBlack = true), settings.currentAppPrefs())
        assertEquals(ReaderPrefs(readingFlow = ReadingFlow.RTL), settings.currentReaderPrefs())
        job.cancelAndJoin()
    }

    @Test fun `concurrent same-domain updates converge on one written value`() = runBlocking {
        val (settings, job) = open(file())
        val sizes = (1..16).map { AppPrefs.MIN_CACHE_MIB + it * 100 }
        coroutineScope {
            sizes.map { size -> async { settings.updateApp { it.copy(cacheSizeMiB = size) } } }.forEach { it.await() }
        }
        // Last-writer-wins on one field is the contract; the guarantee is no torn write outside the set.
        assertTrue(settings.currentAppPrefs().cacheSizeMiB in sizes)
        job.cancelAndJoin()
    }

    @Test fun `the current-value accessors agree with the flows`() = runBlocking {
        val (settings, job) = open(file())
        settings.updateApp { it.copy(showHiddenFolders = true) }
        settings.updateReader { it.copy(readingFlow = ReadingFlow.VERTICAL) }
        assertEquals(settings.appPrefs.first(), settings.currentAppPrefs())
        assertEquals(settings.readerPrefs.first(), settings.currentReaderPrefs())
        job.cancelAndJoin()
    }

    @Test fun `removing the last library location persists, including across reopening`() = runBlocking {
        val f = file()
        val (settings, job) = open(f)
        settings.updateApp { it.copy(locations = setOf("content://tree/a")) }
        assertEquals(setOf("content://tree/a"), settings.currentAppPrefs().locations)

        settings.updateApp { it.copy(locations = emptySet()) }
        assertEquals(emptySet<String>(), settings.currentAppPrefs().locations)
        job.cancelAndJoin()

        // Reopening reads straight off disk: proves the key was actually removed by the empty-set
        // write, not just masked in memory until the next decode of the same live store.
        val (reopened, reopenedJob) = open(f)
        assertEquals(emptySet<String>(), reopened.currentAppPrefs().locations)
        reopenedJob.cancelAndJoin()
    }

    @Test fun `a wrongly typed app entry falls back for that field only, and a write repairs it`() = runBlocking {
        val f = file()
        seed(f) {
            it[stringPreferencesKey(PrefCodec.KEY_CACHE_MIB)] = "lots"
            it[stringPreferencesKey(PrefCodec.KEY_NIGHT_MODE)] = NightMode.ON.name
        }

        val (settings, settingsJob) = open(f)
        assertEquals(AppPrefs(nightMode = NightMode.ON), settings.currentAppPrefs())
        settings.updateApp { it.copy(cacheSizeMiB = 256) }
        assertEquals(AppPrefs(nightMode = NightMode.ON, cacheSizeMiB = 256), settings.currentAppPrefs())
        settingsJob.cancelAndJoin()
    }

    @Test fun `rendering colour round-trips and survives reopen`() = runBlocking {
        val f = file()
        val expected = RenderingPrefs(
            upscaler = Upscaler.LANCZOS,
            colour = ColourParams(
                brightness = 0.15f,
                contrast = 1.1f,
                saturation = 1.25f,
                temperature = -0.4f,
                wbAggression = 0.8f,
                vibrance = 0.6f,
                gamma = 1.1f,
                gammaR = 0.9f,
                gammaG = 1f,
                gammaB = 1.2f,
            ),
        )
        val (first, firstJob) = open(f)
        first.updateRendering { expected }
        assertEquals(expected, first.currentRenderingPrefs())
        firstJob.cancelAndJoin()

        val (reopened, job) = open(f)
        assertEquals(expected, reopened.currentRenderingPrefs())
        job.cancelAndJoin()
    }

    @Test fun `a wrongly typed colour entry falls back and a raw out-of-range one clamps`() = runBlocking {
        val f = file()
        seed(f) {
            it[stringPreferencesKey(PrefCodec.KEY_COLOUR_BRIGHTNESS)] = "bright"
            it[floatPreferencesKey(PrefCodec.KEY_COLOUR_CONTRAST)] = 9f
        }

        val (settings, settingsJob) = open(f)
        val colour = settings.currentRenderingPrefs().colour
        assertEquals(0f, colour.brightness)
        assertEquals(ColourParams.CONTRAST_RANGE.endInclusive, colour.contrast)
        settingsJob.cancelAndJoin()
    }
}
