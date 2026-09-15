package com.absolutex.remote.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialStoreTest {

    @Test fun roundTripCopiesSecrets() {
        val store = InMemoryCredentialStore()
        val original = "s3cret".toCharArray()
        store.save("komga", original)
        original.fill('x')
        assertArrayEquals("s3cret".toCharArray(), store.load("komga"))
    }

    @Test fun clearRemoves() {
        val store = InMemoryCredentialStore()
        store.save("komga", "s3cret".toCharArray())
        store.clear("komga")
        assertNull(store.load("komga"))
    }

    @Test fun overwriteReplaces() {
        val store = InMemoryCredentialStore()
        store.save("komga", "old".toCharArray())
        store.save("komga", "new".toCharArray())
        assertArrayEquals("new".toCharArray(), store.load("komga"))
    }
}
