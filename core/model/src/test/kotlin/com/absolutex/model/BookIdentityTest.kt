package com.absolutex.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BookIdentityTest {

    @Test fun `the same comic reached by different routes has one identity`() {
        // Shelf tap (file path), SAF pick (content Uri) and scanner all observe the same
        // filename and size, which is the whole point.
        val viaShelf = BookIdentity.of("Absolute Batman 001.cbr", 56_093_689)
        val viaPicker = BookIdentity.ofOrFallback("Absolute Batman 001.cbr", 56_093_689, "content://x")
        assertEquals(viaShelf, viaPicker)
    }

    @Test fun `different size is a different book`() {
        assertNotEquals(BookIdentity.of("Batman 001.cbz", 100), BookIdentity.of("Batman 001.cbz", 101))
    }

    @Test fun `a provider that omits the size falls back instead of colliding`() {
        // Two unrelated books with no size must not share "name:null".
        assertEquals("content://a", BookIdentity.ofOrFallback("Batman 001.cbz", null, "content://a"))
        assertEquals("content://b", BookIdentity.ofOrFallback("Batman 001.cbz", null, "content://b"))
    }

    @Test fun `a missing or blank name falls back`() {
        assertEquals("u", BookIdentity.ofOrFallback(null, 10, "u"))
        assertEquals("u", BookIdentity.ofOrFallback("  ", 10, "u"))
    }

    @Test fun `an unknown size reported as negative falls back`() {
        // OpenableColumns.SIZE can legitimately be absent; some providers report -1.
        assertEquals("u", BookIdentity.ofOrFallback("a.cbz", -1, "u"))
    }

    @Test fun `a zero-byte file still has an identity`() {
        assertEquals("empty.cbz:0", BookIdentity.ofOrFallback("empty.cbz", 0, "u"))
    }
}
