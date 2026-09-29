package com.absolutex.core.data.backup

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.PageView
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.settings.pendingFavourites
import com.absolutex.core.data.settings.restoreBackup
import com.absolutex.model.ReadingFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest : BackupFixture() {
    @Test fun `reading tables restore without any library rows and round trip`() = runTest {
        val data = BackupData("test", listOf(ReadingProgress("a.cbz:12", 2, 10, 100)),
            listOf(PageView(bookKey = "a.cbz:12", page = 2, atEpochMs = 100)),
            listOf(Bookmark("a.cbz:12", 2, 100)), listOf(BookPrefs("a.cbz:12", "RTL", "DOUBLE")),
            setOf("a.cbz:12"), mapOf("reading_flow" to "RTL", "colour_brightness" to 0.1f))
        assertEquals(RestoreResult.Complete(1), repository.restore(BackupWriter.write(data).inputStream()))
        assertTrue(db.libraryDao().allOnce().isEmpty())
        val exported = BackupCodec.read(repository.export("test").inputStream())
        assertEquals(data.progress, exported.progress)
        assertEquals(data.history, exported.history.map { it.copy(id = 0) })
        assertEquals(data.bookmarks, exported.bookmarks)
        assertEquals(data.bookPrefs, exported.bookPrefs)
        assertEquals(data.favourites, exported.favourites)
        val bytes = repository.export("test")
        repository.restore(bytes.inputStream())
        assertArrayEquals(bytes, repository.export("test"))
    }

    @Test fun `credentials server records and SAF grants never leave the device`() = runTest {
        settings.updateApp { it.copy(locations = setOf("content://secret/grant")) }
        store.edit {
            it[stringPreferencesKey("password")] = "secret-password"
            it[stringPreferencesKey("server_record")] = "smb://private-host"
        }
        val text = repository.export("test").toString(Charsets.UTF_8)
        listOf("password", "secret-password", "server_record", "private-host", "content://", "library_locations")
            .forEach { assertFalse(text.contains(it)) }
        repository.restore(
            """{"schemaVersion":1,"appVersion":"old","preferences":{
                "library_locations":["content://bad"],"password":"x"}}""".byteInputStream(),
        )
        assertEquals(setOf("content://secret/grant"), settings.currentAppPrefs().locations)
    }

    @Test fun `old backups preserve defaults and existing preferences when keys are omitted`() = runTest {
        val defaults = settings.currentReaderPrefs()
        repository.restore("""{"schemaVersion":1,"appVersion":"old"}""".byteInputStream())
        assertEquals(defaults, settings.currentReaderPrefs())
        settings.updateReader { it.copy(readingFlow = ReadingFlow.RTL) }
        repository.restore(
            """{"schemaVersion":1,"appVersion":"old","preferences":{"true_black":false}}""".byteInputStream(),
        )
        assertEquals(ReadingFlow.RTL, settings.currentReaderPrefs().readingFlow)
        assertFalse(settings.currentAppPrefs().trueBlack)
    }

    @Test fun `preferences use codec fallback and bounds`() = runTest {
        repository.restore(
            """{"schemaVersion":1,"appVersion":"test","preferences":{"night_mode":"FUTURE",
                "cache_size_mib":-100,"page_turn_ms":9999,"colour_brightness":100}}""".byteInputStream(),
        )
        assertEquals(64, settings.currentAppPrefs().cacheSizeMiB)
        assertEquals(600, settings.currentReaderPrefs().pageTurnMs)
        assertEquals(com.absolutex.core.data.settings.AppPrefs().nightMode, settings.currentAppPrefs().nightMode)
    }

    @Test fun `pending capacity failure changes neither store`() = runTest {
        settings.restoreBackup(emptyMap(), (1..MAX_BOOKS).map { "book$it:1" }.toSet())
        val before = repository.export("test")
        val data = BackupData("test", listOf(ReadingProgress("extra:1", 0, 1, 1)), favourites = setOf("extra:1"))
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.restore(BackupWriter.write(data).inputStream()) }
        }
        assertArrayEquals(before, repository.export("test"))
        assertEquals(MAX_BOOKS, settings.pendingFavourites().size)
    }
}
