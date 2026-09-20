package com.absolutex.remote.sync

import com.absolutex.remote.core.InMemoryCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SMB/FTP secret isolation behind [SyncSecrets]. JVM-only via [InMemoryCredentialStore] —
 * no Robolectric, no org.json needed.
 */
class ServerSecretsTest {

    @Test fun `service ids are distinct across servers and secret kinds`() {
        val ids = setOf(
            SyncSecrets.apiKeyService("s1"),
            SyncSecrets.passwordService("s1"),
            SyncSecrets.smbPasswordService("s1"),
            SyncSecrets.ftpPasswordService("s1"),
            SyncSecrets.apiKeyService("s2"),
            SyncSecrets.passwordService("s2"),
            SyncSecrets.smbPasswordService("s2"),
            SyncSecrets.ftpPasswordService("s2"),
        )
        assertEquals(8, ids.size)
    }

    @Test fun `smb and ftp passwords round-trip per server`() {
        val secrets = SyncSecrets(InMemoryCredentialStore())
        secrets.saveSmbPassword("s1", "smb-one".toCharArray())
        secrets.saveFtpPassword("s1", "ftp-one".toCharArray())
        secrets.saveSmbPassword("s2", "smb-two".toCharArray())
        secrets.saveFtpPassword("s2", "ftp-two".toCharArray())
        assertEquals("smb-one", secrets.loadSmbPassword("s1")?.concatToString())
        assertEquals("ftp-one", secrets.loadFtpPassword("s1")?.concatToString())
        assertEquals("smb-two", secrets.loadSmbPassword("s2")?.concatToString())
        assertEquals("ftp-two", secrets.loadFtpPassword("s2")?.concatToString())
    }

    @Test fun `clearServer wipes one server and leaves the other intact`() {
        val secrets = SyncSecrets(InMemoryCredentialStore())
        for (id in listOf("s1", "s2")) {
            secrets.saveApiKey(id, "key-$id".toCharArray())
            secrets.savePassword(id, "pass-$id".toCharArray())
            secrets.saveSmbPassword(id, "smb-$id".toCharArray())
            secrets.saveFtpPassword(id, "ftp-$id".toCharArray())
        }
        secrets.clearServer("s1")
        assertNull(secrets.loadApiKey("s1"))
        assertNull(secrets.loadPassword("s1"))
        assertNull(secrets.loadSmbPassword("s1"))
        assertNull(secrets.loadFtpPassword("s1"))
        assertEquals("key-s2", secrets.loadApiKey("s2")?.concatToString())
        assertEquals("pass-s2", secrets.loadPassword("s2")?.concatToString())
        assertEquals("smb-s2", secrets.loadSmbPassword("s2")?.concatToString())
        assertEquals("ftp-s2", secrets.loadFtpPassword("s2")?.concatToString())
    }

    @Test fun `missing secrets load null`() {
        val secrets = SyncSecrets(InMemoryCredentialStore())
        assertNull(secrets.loadApiKey("nope"))
        assertNull(secrets.loadPassword("nope"))
        assertNull(secrets.loadSmbPassword("nope"))
        assertNull(secrets.loadFtpPassword("nope"))
    }

    @Test fun `single-kind clears wipe only their slot`() {
        val secrets = SyncSecrets(InMemoryCredentialStore())
        secrets.saveSmbPassword("s", "smb".toCharArray())
        secrets.saveFtpPassword("s", "ftp".toCharArray())
        secrets.savePassword("s", "pw".toCharArray())
        secrets.clearSmbPassword("s")
        assertNull(secrets.loadSmbPassword("s"))
        assertEquals("ftp", secrets.loadFtpPassword("s")?.concatToString())
        secrets.clearFtpPassword("s")
        assertNull(secrets.loadFtpPassword("s"))
        assertEquals("pw", secrets.loadPassword("s")?.concatToString())
    }
}
