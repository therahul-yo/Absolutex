package com.absolutex.remote.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.absolutex.remote.core.InMemoryCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Removing a server wipes all of its secrets and none of any other server's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerAdminTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `remove wipes the record and all four secrets`() = runTest {
        val servers = RemoteServers(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + Job()),
                produceFile = { File(tmp.root, "remote.preferences_pb") },
            ),
        )
        val secrets = SyncSecrets(InMemoryCredentialStore())
        servers.save(SmbServer("gone", "nas", "comics", "books", username = "u"))
        servers.save(SmbServer("kept", "nas", "comics", "books", username = "u"))
        for (id in listOf("gone", "kept")) {
            secrets.savePassword(id, "pw-$id".toCharArray())
            secrets.saveApiKey(id, "key-$id".toCharArray())
            secrets.saveSmbPassword(id, "smb-$id".toCharArray())
            secrets.saveFtpPassword(id, "ftp-$id".toCharArray())
        }
        ServerAdmin(servers, secrets).removeServer("gone")
        assertEquals(listOf("kept"), servers.current().map { it.id })
        assertNull(secrets.loadPassword("gone"))
        assertNull(secrets.loadApiKey("gone"))
        assertNull(secrets.loadSmbPassword("gone"))
        assertNull(secrets.loadFtpPassword("gone"))
        assertTrue(secrets.loadPassword("kept")?.contentEquals("pw-kept".toCharArray()) == true)
        assertTrue(secrets.loadSmbPassword("kept")?.contentEquals("smb-kept".toCharArray()) == true)
    }
}
