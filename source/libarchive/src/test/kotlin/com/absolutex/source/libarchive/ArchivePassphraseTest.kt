package com.absolutex.source.libarchive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class ArchivePassphraseTest {
    @Test fun copies_caller_chars_as_standard_utf8_and_clears_call_snapshot() {
        val chars = "test-\uD83D\uDD11".toCharArray()
        val expected = "test-\uD83D\uDD11".toByteArray(Charsets.UTF_8)
        val owned = ArchivePassphrase(chars)
        chars.fill('\u0000')
        var observed: ByteArray? = null
        owned.use {
            it.useBytes { bytes ->
                observed = bytes
                assertArrayEquals(expected, bytes)
            }
            assertTrue(observed!!.all { byte -> byte == 0.toByte() })
            it.useBytes { bytes -> assertArrayEquals(expected, bytes) }
        }
        assertThrows(IOException::class.java) { owned.useBytes { error("must not run") } }
    }

    @Test fun cancellation_and_errors_propagate_unchanged_and_clear_snapshot() {
        val failures = listOf(CancellationException("cancel"), AssertionError("failure"), IOException("io"))
        ArchivePassphrase("test".toCharArray()).use { owned ->
            failures.forEach { failure ->
                var snapshot: ByteArray? = null
                val thrown = assertThrows(failure.javaClass) {
                    owned.useBytes { bytes ->
                        snapshot = bytes
                        throw failure
                    }
                }
                assertSame(failure, thrown)
                assertTrue(snapshot!!.all { it == 0.toByte() })
            }
        }
    }

    @Test fun forgetting_unneeded_password_does_not_close_source() {
        ArchivePassphrase("test".toCharArray()).use { owned ->
            owned.forget()
            assertEquals(42, owned.useBytes { assertNull(it); 42 })
        }
    }

    @Test fun rejects_empty_or_nul_password_instead_of_truncating() {
        assertThrows(IllegalArgumentException::class.java) { ArchivePassphrase(charArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { ArchivePassphrase(charArrayOf('a', '\u0000', 'b')) }
    }
}
