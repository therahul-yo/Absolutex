package com.absolutex.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class BaseKeyTest {

    private fun key(page: Int, width: Int = 100, height: Int = 200) = BaseKey("book", page, width, height)

    @Test fun `a resize drops the old size for the page just redecoded`() {
        val old = key(page = 5, width = 100, height = 200)
        val resized = key(page = 5, width = 300, height = 600)
        val kept = baseKeysToKeep(setOf(old, resized), settledPage = 5, window = 2, justDecoded = resized)
        assertEquals(setOf(resized), kept)
    }

    @Test fun `every page within the window survives, at its own size`() {
        val keys = setOf(key(4), key(5), key(6))
        val kept = baseKeysToKeep(keys, settledPage = 5, window = 2, justDecoded = key(5))
        assertEquals(keys, kept)
    }

    @Test fun `a page outside the window is dropped`() {
        val near = key(5)
        val far = key(50)
        val kept = baseKeysToKeep(setOf(near, far), settledPage = 5, window = 2, justDecoded = near)
        assertEquals(setOf(near), kept)
    }

    @Test fun `a resize of a neighbouring page, not the one just decoded, is untouched`() {
        // Only the page just decoded is size-bounded here; a neighbour's own stale size is left
        // for its own next decode to clean up, exactly as this window already treats every page.
        val neighbourOldSize = key(page = 4, width = 100, height = 200)
        val justDecoded = key(page = 5)
        val kept = baseKeysToKeep(setOf(neighbourOldSize, justDecoded), settledPage = 5, window = 2, justDecoded)
        assertEquals(setOf(neighbourOldSize, justDecoded), kept)
    }
}
