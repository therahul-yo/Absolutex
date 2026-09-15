package com.absolutex.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The cache key must distinguish every axis that changes a tile's pixels. If it collides,
 * a zoomed-in tile gets served at the wrong resolution and the page renders soft — a bug
 * that looks like "the renderer is blurry" rather than like a cache fault.
 */
private const val BOOK = "book-a"

class TileKeyTest {

    @Test fun `same coordinates at different subsamples are different tiles`() {
        assertNotEquals(TileKey(0, 1, 1, 1, BOOK), TileKey(0, 1, 1, 2, BOOK))
    }

    @Test fun `same tile position on different pages does not collide`() {
        assertNotEquals(TileKey(0, 2, 3, 1, BOOK), TileKey(1, 2, 3, 1, BOOK))
    }

    @Test fun `column and row are not interchangeable`() {
        assertNotEquals(TileKey(0, 2, 3, 1, BOOK), TileKey(0, 3, 2, 1, BOOK))
    }

    @Test fun `the same tile in two different books is not the same key`() {
        // Regression: bookId defaulted to "" and every call site took the default, so tile
        // keys were identical across books and the scoping was inert.
        assertNotEquals(TileKey(3, 1, 1, 1, "book-a"), TileKey(3, 1, 1, 1, "book-b"))
    }

    @Test fun `identical descriptors are equal and hash alike`() {
        val a = TileKey(4, 2, 3, 2, BOOK)
        val b = TileKey(4, 2, 3, 2, BOOK)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
