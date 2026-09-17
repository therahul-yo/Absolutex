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

    @Test fun `unpaired surrogates substitute without throwing, matching the old bytes`() {
        // Explicit REPLACE, pinned: the naive hand-rolled alternative sets REPORT and throws
        // MalformedInputException on a paste artifact. The JDK's default substitution ('?')
        // is asserted byte-for-byte against the String path this replaces, so behaviour is
        // preserved, not just silent. (Surrogates built from code points: backslash-u escapes
        // in this file would be interpreted before kotlinc ever sees them.)
        val loneHigh = String(charArrayOf(0xD800.toChar()))
        val loneLow = String(charArrayOf(0xDC00.toChar()))
        for (lone in listOf(loneHigh, loneLow, "a" + loneHigh + "b", loneLow + loneHigh)) {
            val bytes = lone.toCharArray().toUtf8Bytes()
            assertTrue(bytes.contentEquals(lone.toByteArray(Charsets.UTF_8)))
            // Stable: re-decoding substitutes identically instead of growing or throwing.
            val text = bytes.toChars().concatToString()
            assertTrue(text.toCharArray().toUtf8Bytes().contentEquals(bytes))
        }
    }

    @Test fun `output length is exact, never scratch capacity`() {
        // The scratch is sized 4x for UTF-8; returning it unflipped would leak trailing
        // zeros into the ciphertext and break the Keystore round-trip. "é€𝄞" is 2+3+4.
        assertEquals(9, "é€𝄞".toCharArray().toUtf8Bytes().size)
        assertEquals(0, CharArray(0).toUtf8Bytes().size)
    }
}
