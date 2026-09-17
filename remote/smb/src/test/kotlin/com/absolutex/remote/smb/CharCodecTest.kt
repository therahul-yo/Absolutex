package com.absolutex.remote.smb

import com.absolutex.remote.smb.CharCodec.toChars
import com.absolutex.remote.smb.CharCodec.toUtf8Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The credential bytes never take the String round-trip the CharArray contract forbids. */
class CharCodecTest {

    @Test fun `round-trip preserves ascii and non-ascii`() {
        val password = "s3cret-pässwörd-🔑".toCharArray()
        val bytes = password.toUtf8Bytes()
        // Byte-identical to the platform encoding: the encoder path carries the same data
        // the old String path did, minus the unscrubbable copy.
        assertTrue(bytes.contentEquals(String(password).toByteArray(Charsets.UTF_8)))
        assertTrue(bytes.toChars().contentEquals(password))
    }

    @Test fun `empty password round-trips`() {
        val bytes = CharArray(0).toUtf8Bytes()
        assertEquals(0, bytes.size)
        assertEquals(0, bytes.toChars().size)
    }

    @Test fun `multibyte boundaries survive`() {
        // Two-byte, three-byte and surrogate-pair encodings back to back.
        val password = "é€𝄞".toCharArray()
        assertTrue(password.toUtf8Bytes().toChars().contentEquals(password))
    }
}
