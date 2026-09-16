package com.absolutex.remote.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtpLocationTest {

    private fun location(
        host: String = "files.example.com",
        port: Int = 21,
        username: String = "reader",
        path: String = "/comics/book.cbz",
        useTls: Boolean = true,
    ) = FtpLocation(host, port, username, path, useTls)

    @Test fun `ftps location reports the ftps scheme`() {
        assertEquals("ftps", location(useTls = true).scheme)
    }

    @Test fun `plain ftp location reports the ftp scheme`() {
        assertEquals("ftp", location(useTls = false).scheme)
    }

    @Test fun `uri carries identity but never a password`() {
        val uri = location().uri
        assertTrue(uri.startsWith("ftps://reader@files.example.com:21/comics/book.cbz"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank host rejected`() {
        location(host = "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `port zero rejected`() {
        location(port = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `port past 65535 rejected`() {
        location(port = 70_000)
    }

    @Test fun `port boundaries accepted`() {
        location(port = 1)
        location(port = 65_535)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank username rejected`() {
        location(username = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank path rejected`() {
        location(path = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dot-dot traversal rejected`() {
        location(path = "/comics/../../etc/passwd")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `backslash traversal rejected`() {
        location(path = "/comics/..\\secret.cbz")
    }
}
