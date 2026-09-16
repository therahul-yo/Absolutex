package com.absolutex.remote.ftp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtpCredentialStoreTest {

    private val key = "ftps://reader@files.example.com:990/comics"

    @Test fun `round-trip`() {
        val store = InMemoryFtpCredentialStore()
        store.save(key, "s3cr3t".toCharArray())
        assertArrayEquals("s3cr3t".toCharArray(), store.load(key))
    }

    @Test fun `missing key loads null`() {
        assertNull(InMemoryFtpCredentialStore().load("nope"))
    }

    @Test fun `clear removes and second clear is safe`() {
        val store = InMemoryFtpCredentialStore()
        store.save(key, "s3cr3t".toCharArray())
        store.clear(key)
        assertNull(store.load(key))
        store.clear(key)
    }

    @Test fun `save overwrites`() {
        val store = InMemoryFtpCredentialStore()
        store.save(key, "first".toCharArray())
        store.save(key, "second".toCharArray())
        assertArrayEquals("second".toCharArray(), store.load(key))
    }

    @Test fun `load returns a copy`() {
        val store = InMemoryFtpCredentialStore()
        store.save(key, "s3cr3t".toCharArray())
        val loaded = requireNotNull(store.load(key))
        loaded.fill('x')
        assertArrayEquals("s3cr3t".toCharArray(), store.load(key))
    }

    @Test fun `save copies its input`() {
        val store = InMemoryFtpCredentialStore()
        val input = "s3cr3t".toCharArray()
        store.save(key, input)
        input.fill('x')
        assertArrayEquals("s3cr3t".toCharArray(), store.load(key))
    }
}
