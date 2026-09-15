package com.absolutex.core.data.settings

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private fun file() = File(tmp.root, "settings.preferences_pb")

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
        val job = Job()
        val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { f })
        raw.edit {
            it[intPreferencesKey(PrefCodec.KEY_READING_FLOW)] = 7
            it[stringPreferencesKey(PrefCodec.KEY_FIT_MODE)] = FitMode.FULL_SIZE.name
        }
        job.cancelAndJoin()

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
}
