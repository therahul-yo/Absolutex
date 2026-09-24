package com.absolutex.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover shared-element contract both ends key by: grid cards hide exactly their own art
 * while open, and the reader hero covers exactly the decoding window. Pure Kotlin, so asserted
 * on the JVM with no device.
 */
class CoverTransitionTest {

    @Test
    fun `key is stable and namespaced per book`() {
        assertEquals("cover:/books/one.cbz", coverTransitionKey("/books/one.cbz"))
    }

    @Test
    fun `keys differ per book`() {
        assertFalse(coverTransitionKey("/books/one.cbz") == coverTransitionKey("/books/two.cbz"))
    }

    @Test
    fun `no open book leaves every cover drawn`() {
        assertTrue(isCoverSourceVisible(null, "/books/one.cbz"))
    }

    @Test
    fun `open book hides only its own cover`() {
        assertFalse(isCoverSourceVisible("/books/one.cbz", "/books/one.cbz"))
        assertTrue(isCoverSourceVisible("/books/one.cbz", "/books/two.cbz"))
    }

    @Test
    fun `hero waits for the loading cycle, not just settled state`() {
        // A stale settled state from the previous book must not dismiss the hero early.
        val stale = isCoverHeroReady(
            seenLoading = false, loading = false, error = null, pageCount = 42, textEpub = false,
        )
        assertFalse(stale)
        val loading = isCoverHeroReady(
            seenLoading = true, loading = true, error = null, pageCount = 0, textEpub = false,
        )
        assertFalse(loading)
    }

    @Test
    fun `hero retires once pages settle`() {
        val ready = isCoverHeroReady(
            seenLoading = true, loading = false, error = null, pageCount = 42, textEpub = false,
        )
        assertTrue(ready)
    }

    @Test
    fun `hero retires for the text reader, which has no page count`() {
        val ready = isCoverHeroReady(
            seenLoading = true, loading = false, error = null, pageCount = 0, textEpub = true,
        )
        assertTrue(ready)
    }

    @Test
    fun `hero stays on failure, so the error is never covered`() {
        val failed = isCoverHeroReady(
            seenLoading = true, loading = false, error = "unreadable", pageCount = 0, textEpub = false,
        )
        assertFalse(failed)
    }
}
