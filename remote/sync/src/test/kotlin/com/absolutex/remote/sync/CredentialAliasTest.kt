package com.absolutex.remote.sync

import com.absolutex.remote.core.InMemoryCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Two servers — or a key and a password on one server — can never share a credential file:
 * service ids differ, and the store hashes them to full SHA-256 hex, so even adversarial
 * ids cannot collide (the SMB lane proved the same property for its own store).
 */
class CredentialAliasTest {

    @Test fun `service ids differ per server and per secret kind`() {
        val ids = setOf(
            SyncSecrets.apiKeyService("s1"),
            SyncSecrets.passwordService("s1"),
            SyncSecrets.apiKeyService("s2"),
            SyncSecrets.passwordService("s2"),
        )
        assertEquals(4, ids.size)
    }

    @Test fun `aliases hash distinctly, including separator tricks`() {
        val hashed = setOf(
            hashService("sync/a/api-key"),
            hashService("sync\\a\\api-key"),
            hashService("sync%a/api-key"),
            hashService("sync/a/password"),
        )
        assertEquals(4, hashed.size)
    }

    @Test fun `secrets round-trip per server without cross-talk`() {
        val store = InMemoryCredentialStore()
        val secrets = SyncSecrets(store)
        secrets.saveApiKey("s1", "key-one".toCharArray())
        secrets.savePassword("s1", "pass-one".toCharArray())
        secrets.saveApiKey("s2", "key-two".toCharArray())
        assertEquals("key-one", secrets.loadApiKey("s1")?.concatToString())
        assertEquals("pass-one", secrets.loadPassword("s1")?.concatToString())
        assertEquals("key-two", secrets.loadApiKey("s2")?.concatToString())
        assertEquals(null, secrets.loadPassword("s2"))
        secrets.clearServer("s1")
        assertEquals(null, secrets.loadApiKey("s1"))
        assertEquals("key-two", secrets.loadApiKey("s2")?.concatToString())
    }

    @Test fun `hash is stable and full-length hex`() {
        assertEquals(hashService("sync/s1/api-key"), hashService("sync/s1/api-key"))
        assertNotEquals(hashService("sync/s1/api-key"), hashService("sync/s1/password"))
        assertEquals(64, hashService("x").length)
    }
}
