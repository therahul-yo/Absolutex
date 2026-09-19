package com.absolutex.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Test

/**
 * The buffered-body ceiling. The case that matters is the one past the cap: a truncated body
 * is worse than a refused one, because JSON that stops mid-object throws somewhere unrelated
 * and a short image decodes to a corrupt page — both blame the wrong thing.
 */
class HttpBodyCapTest {

    private fun streamOf(size: Int) = ByteArrayInputStream(ByteArray(size) { it.toByte() })

    @Test fun `body under the cap is returned whole`() {
        val bytes = streamOf(64).readCapped(1024)
        assertEquals(64, bytes.size)
    }

    @Test fun `body exactly at the cap is served, not refused`() {
        // The cap is a ceiling, not a strict bound: an entry sitting exactly on it is legal,
        // which is why the read goes one byte further rather than stopping at the limit.
        val bytes = streamOf(1024).readCapped(1024)
        assertEquals(1024, bytes.size)
    }

    @Test fun `body one byte over the cap throws instead of truncating`() {
        try {
            streamOf(1025).readCapped(1024)
            fail("expected IOException for a body past the cap")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("larger than 1024") == true)
        }
    }

    @Test fun `an empty body is not an error`() {
        assertEquals(0, streamOf(0).readCapped(1024).size)
    }

    @Test fun `a zero cap admits only an empty body`() {
        assertEquals(0, streamOf(0).readCapped(0).size)
        try {
            streamOf(1).readCapped(0)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("larger than 0") == true)
        }
    }

    @Test fun `a negative cap is a caller bug, not an IO failure`() {
        try {
            streamOf(1).readCapped(-1)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("negative cap") == true)
        }
    }

    @Test fun `the shipped default clears one comic page`() {
        // Tied to the bound this codebase already puts on the same artefact: the only
        // requestBytes caller fetches one Komga page image, and CoreComicSource calls
        // anything past 32 MiB hostile rather than a scan. If one moves, both should.
        assertEquals(CoreComicSource.MAX_ENTRY_BYTES, DEFAULT_BODY_MAX_BYTES)
    }
}
