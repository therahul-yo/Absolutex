package com.absolutex.remote.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The path-bound adapter delegates reads and forwards close to the session behind it. */
class FtpBindTest {

    @Test fun `bound reads fix the path and forward close`() {
        val bytes = ByteArray(64) { it.toByte() }
        val transport = FakeFtpTransport(bytes)
        val bound = transport.bind("/books/b.cbz")
        assertEquals(bytes.size.toLong(), bound.sizeBytes())
        assertTrue(bound.readAt(10, 6).contentEquals(bytes.copyOfRange(10, 16)))
        bound.close()
        assertEquals(1, transport.closes)
    }
}
