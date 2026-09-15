package com.absolutex.core.thumbnails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbKeysTest {

    @Test fun `same request always derives the same file name`() {
        val first = ThumbKeys.fileName(ThumbRequest("book-a", 3, ThumbRequest.BUCKET_SMALL))
        val second = ThumbKeys.fileName(ThumbRequest("book-a", 3, ThumbRequest.BUCKET_SMALL))
        assertEquals(first, second)
    }

    @Test fun `page index participates in the key`() {
        val first = ThumbKeys.fileName(ThumbRequest("book-a", 0, ThumbRequest.BUCKET_SMALL))
        val second = ThumbKeys.fileName(ThumbRequest("book-a", 1, ThumbRequest.BUCKET_SMALL))
        assertNotEquals(first, second)
    }

    @Test fun `width bucket participates in the key`() {
        val small = ThumbKeys.fileName(ThumbRequest("book-a", 0, ThumbRequest.BUCKET_SMALL))
        val large = ThumbKeys.fileName(ThumbRequest("book-a", 0, ThumbRequest.BUCKET_LARGE))
        assertNotEquals(small, large)
    }

    @Test fun `source id participates in the key`() {
        val first = ThumbKeys.fileName(ThumbRequest("book-a", 0, ThumbRequest.BUCKET_SMALL))
        val second = ThumbKeys.fileName(ThumbRequest("book-b", 0, ThumbRequest.BUCKET_SMALL))
        assertNotEquals(first, second)
    }

    @Test fun `file names are fixed-length lowercase hex plus suffix`() {
        val name = ThumbKeys.fileName(ThumbRequest("book-a", 0, ThumbRequest.BUCKET_SMALL))
        assertTrue(name, name.matches(Regex("[0-9a-f]+\\.thumb")))
        assertEquals(ThumbKeys.sha256Hex("x").length + ThumbKeys.FILE_SUFFIX.length, name.length)
    }

    @Test fun `sha256 is stable and 64 hex chars`() {
        assertEquals(ThumbKeys.sha256Hex("cover"), ThumbKeys.sha256Hex("cover"))
        assertEquals(64, ThumbKeys.sha256Hex("cover").length)
        assertNotEquals(ThumbKeys.sha256Hex("cover"), ThumbKeys.sha256Hex("page"))
    }

    @Test fun `widths at or below small snap to small`() {
        assertEquals(ThumbRequest.BUCKET_SMALL, ThumbRequest.snapWidth(1))
        assertEquals(ThumbRequest.BUCKET_SMALL, ThumbRequest.snapWidth(ThumbRequest.BUCKET_SMALL))
    }

    @Test fun `widths above small snap to large`() {
        assertEquals(ThumbRequest.BUCKET_LARGE, ThumbRequest.snapWidth(ThumbRequest.BUCKET_SMALL + 1))
        assertEquals(ThumbRequest.BUCKET_LARGE, ThumbRequest.snapWidth(4096))
    }

    @Test fun `non-positive widths snap to small instead of crashing`() {
        assertEquals(ThumbRequest.BUCKET_SMALL, ThumbRequest.snapWidth(0))
        assertEquals(ThumbRequest.BUCKET_SMALL, ThumbRequest.snapWidth(-40))
    }

    @Test fun `of preserves identity and snaps the width`() {
        val request = ThumbRequest.of("book-a", 7, 300)
        assertEquals("book-a", request.sourceId)
        assertEquals(7, request.pageIndex)
        assertEquals(ThumbRequest.BUCKET_LARGE, request.widthBucket)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unbucketed widths are rejected`() {
        ThumbRequest("book-a", 0, 300)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative pages are rejected`() {
        ThumbRequest("book-a", -1, ThumbRequest.BUCKET_SMALL)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank source ids are rejected`() {
        ThumbRequest("  ", 0, ThumbRequest.BUCKET_SMALL)
    }
}
