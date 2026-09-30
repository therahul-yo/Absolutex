package com.absolutex.source.libarchive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolidCachePolicyTest {
    private val gib = 1L shl 30

    @Test fun the_cap_is_a_quarter_of_the_free_space_up_to_one_gib() {
        assertEquals(gib, SolidCachePolicy.capBytes(freeBytes = 64 * gib, cacheBytes = 0))
        assertEquals(gib / 2, SolidCachePolicy.capBytes(freeBytes = 2 * gib, cacheBytes = 0))
    }

    @Test fun space_the_cache_already_holds_counts_as_available_to_it() {
        assertEquals(gib / 2, SolidCachePolicy.capBytes(freeBytes = gib, cacheBytes = gib))
    }

    @Test fun a_full_disk_gives_a_cap_too_small_for_any_book() {
        val cap = SolidCachePolicy.capBytes(freeBytes = 0, cacheBytes = 0)
        assertEquals(0, cap)
        assertFalse(SolidCachePolicy.worthCaching(SolidCachePolicy.MIN_BOOK_BYTES, cap))
        assertEquals("a negative free-space reading is treated as none", 0, SolidCachePolicy.capBytes(-5, 0))
    }

    @Test fun only_a_book_between_the_minimum_and_the_cap_is_worth_caching() {
        val cap = gib
        assertFalse(SolidCachePolicy.worthCaching(SolidCachePolicy.MIN_BOOK_BYTES - 1, cap))
        assertTrue(SolidCachePolicy.worthCaching(SolidCachePolicy.MIN_BOOK_BYTES, cap))
        assertTrue(SolidCachePolicy.worthCaching(cap, cap))
        assertFalse(SolidCachePolicy.worthCaching(cap + 1, cap))
    }

    @Test fun a_cache_key_is_a_stable_hash_that_hides_what_it_was_made_from() {
        val uri = "content://com.android.providers/document/primary%3ADownload%2FSecret%20Book.cb7"
        val key = SolidCacheKey.of(uri, 12_345, 99)
        assertEquals(key, SolidCacheKey.of(uri, 12_345, 99))
        assertTrue(Regex("[0-9a-f]{32}").matches(key))
        assertFalse("Secret" in key)
    }

    @Test fun a_replaced_file_gets_a_new_key() {
        val base = SolidCacheKey.of("file:///a.cb7", 100, 5)
        assertNotEquals(base, SolidCacheKey.of("file:///a.cb7", 101, 5))
        assertNotEquals(base, SolidCacheKey.of("file:///a.cb7", 100, 6))
        assertNotEquals(base, SolidCacheKey.of("file:///b.cb7", 100, 5))
    }
}
