package com.absolutex.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbLocationTest {

    @Test fun `valid location keeps identity fields`() {
        val location = SmbLocation(
            host = "nas.local",
            share = "comics",
            path = "manga/berserk v41.cbz",
            port = 445,
            username = "reader",
        )
        assertEquals("nas.local", location.host)
    }

    @Test fun `blank host rejected`() {
        try {
            SmbLocation(host = " ", share = "s", path = "b.cbz", port = 445, username = "u")
            error("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("host") == true)
        }
    }

    @Test fun `path escaping share rejected`() {
        try {
            SmbLocation(host = "h", share = "s", path = "a/../../secret.cbz", port = 445, username = "u")
            error("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("escapes") == true)
        }
    }

    @Test fun `port range enforced`() {
        try {
            SmbLocation(host = "h", share = "s", path = "b.cbz", port = 0, username = "u")
            error("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("port") == true)
        }
    }

    @Test fun `signing opt-out defaults off`() {
        // Flipping this default exposes every share to response rewriting; pin it.
        val location = SmbLocation(host = "h", share = "s", path = "b.cbz", port = 445, username = "u")
        assertEquals(false, location.allowUnsigned)
    }
}

class InMemoryCredentialStoreTest {

    @Test fun `round-trip returns equal copy`() {
        val store = InMemoryCredentialStore()
        store.store("nas", "s3cret".toCharArray())
        assertTrue(store.retrieve("nas")?.contentEquals("s3cret".toCharArray()) == true)
    }

    @Test fun `missing alias returns null`() {
        assertNull(InMemoryCredentialStore().retrieve("nas"))
    }

    @Test fun `clear removes secret`() {
        val store = InMemoryCredentialStore()
        store.store("nas", "s3cret".toCharArray())
        store.clear("nas")
        assertNull(store.retrieve("nas"))
    }

    @Test fun `stored copy is independent of caller array`() {
        val store = InMemoryCredentialStore()
        val password = "s3cret".toCharArray()
        store.store("nas", password)
        password.fill('x')
        assertEquals("s3cret", store.retrieve("nas")?.concatToString())
    }
}
