package com.absolutex.remote.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.absolutex.remote.core.InMemoryCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The M5 → unified-list migration: legacy records keep their ids (so Keystore secrets keyed
 * by id survive untouched), the legacy document clears only on success, and re-runs resume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncServerMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val legacyKey = stringPreferencesKey("servers")

    private fun store(name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + Job()),
        produceFile = { File(tmp.root, name) },
    )

    private fun legacyDocument(): String =
        """[{"id":"a","kind":"KOMGA","baseUrl":"https://komga.lan/","allowCleartext":false,""" +
        """"username":"u","usesApiKey":false},""" +
        """{"id":"b","kind":"KAVITA","baseUrl":"http://kavita.lan:5000","allowCleartext":true,""" +
        """"usesApiKey":true}]"""

    @Test fun `legacy servers import with ids intact and legacy clears`() = runTest {
        val legacy = store("legacy.preferences_pb")
        legacy.edit {
            it[legacyKey] = legacyDocument()
        }
        val remote = RemoteServers(store("remote.preferences_pb"))
        remote.importLegacySyncServers(legacy)
        val servers = remote.current()
        assertEquals(2, servers.size)
        val komga = servers.single { it.id == "a" } as KomgaServer
        assertEquals("https://komga.lan", komga.baseUrl)
        assertEquals("u", komga.username)
        val kavita = servers.single { it.id == "b" } as KavitaServer
        assertTrue(kavita.allowCleartext)
        assertTrue(kavita.usesApiKey)
        assertEquals(null, legacy.data.first()[legacyKey])
    }

    @Test fun `reimport skips ids already present`() = runTest {
        val legacy = store("legacy.preferences_pb")
        legacy.edit { it[legacyKey] = """[{"id":"a","kind":"KOMGA","baseUrl":"https://komga.lan","username":"u"}]""" }
        val remote = RemoteServers(store("remote.preferences_pb"))
        remote.save(KomgaServer("a", "https://komga.lan/api", username = "someone"))
        remote.importLegacySyncServers(legacy)
        // The existing record stands — the legacy twin does not overwrite it.
        assertEquals("https://komga.lan/api", (remote.current().single() as KomgaServer).baseUrl)
        assertEquals(null, legacy.data.first()[legacyKey])
    }

    @Test fun `secrets keyed by server id survive the import`() = runTest {
        val legacy = store("legacy.preferences_pb")
        legacy.edit { it[legacyKey] = """[{"id":"a","kind":"KOMGA","baseUrl":"https://komga.lan","username":"u"}]""" }
        val secrets = SyncSecrets(InMemoryCredentialStore())
        secrets.savePassword("a", "pw".toCharArray())
        secrets.saveApiKey("a", "key".toCharArray())
        RemoteServers(store("remote.preferences_pb")).importLegacySyncServers(legacy)
        assertTrue(secrets.loadPassword("a")?.contentEquals("pw".toCharArray()) == true)
        assertTrue(secrets.loadApiKey("a")?.contentEquals("key".toCharArray()) == true)
    }

    @Test fun `corrupt legacy document is kept, not deleted`() = runTest {
        val legacy = store("legacy.preferences_pb")
        legacy.edit { it[legacyKey] = "{torn" }
        val remote = RemoteServers(store("remote.preferences_pb"))
        try {
            remote.importLegacySyncServers(legacy)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("corrupt") == true)
        }
        // Nothing imported, and the torn document is still there for the next start.
        assertTrue(remote.current().isEmpty())
        assertEquals("{torn", legacy.data.first()[legacyKey])
    }

    @Test fun `failed import resumes without loss or duplication`() = runTest {
        val legacy = store("legacy.preferences_pb")
        legacy.edit {
            it[legacyKey] =
                """[{"id":"a","kind":"KOMGA","baseUrl":"https://a.lan","username":"u"},""" +
                """{"id":"bad","kind":"KOMGA","baseUrl":"","username":"u"},""" +
                """{"id":"b","kind":"KAVITA","baseUrl":"https://b.lan"}]"""
        }
        val remote = RemoteServers(store("remote.preferences_pb"))
        try {
            remote.importLegacySyncServers(legacy)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("blank") == true)
        }
        // A imported before the failure; B never ran; the legacy is intact for retry.
        assertEquals(listOf("a"), remote.current().map { it.id })
        assertTrue(legacy.data.first()[legacyKey]?.contains("bad") == true)
        // Fix the document and re-run: B imports, A is skipped, legacy clears.
        legacy.edit {
            it[legacyKey] =
                """[{"id":"a","kind":"KOMGA","baseUrl":"https://a.lan","username":"u"},""" +
                """{"id":"b","kind":"KAVITA","baseUrl":"https://b.lan"}]"""
        }
        remote.importLegacySyncServers(legacy)
        assertEquals(listOf("a", "b"), remote.current().map { it.id }.sorted())
        assertEquals(null, legacy.data.first()[legacyKey])
    }

    @Test fun `unknown kinds skip while known import`() = runTest {
        val legacy = store("legacy.preferences_pb")
        legacy.edit {
            it[legacyKey] =
                """[{"id":"x","kind":"GOPHER","baseUrl":"gopher://x"},{"id":"a","kind":"KOMGA","baseUrl":"https://k.lan"}]"""
        }
        val remote = RemoteServers(store("remote.preferences_pb"))
        remote.importLegacySyncServers(legacy)
        assertEquals(listOf("a"), remote.current().map { it.id })
    }
}
