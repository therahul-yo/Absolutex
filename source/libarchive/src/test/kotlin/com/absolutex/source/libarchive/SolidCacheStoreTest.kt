package com.absolutex.source.libarchive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SolidCacheStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private val store by lazy { SolidCacheStore(tmp.newFolder("cache")) { now } }

    private fun key(n: Int) = n.toString(16).padStart(32, '0')

    /** An archive's directory holding [bytes] bytes, finished (with a marker) unless [finished] is false. */
    private fun archive(n: Int, bytes: Int, finished: Boolean = true, usedAt: Long = now) {
        val dir = store.dir(key(n)).also { it.mkdirs() }
        File(dir, "00000.bin").writeBytes(ByteArray(bytes))
        if (finished) {
            store.markComplete(key(n), 1)
            File(dir, "complete").setLastModified(usedAt)
        }
    }

    private fun exists(n: Int) = store.dir(key(n)).exists()

    @Test fun the_least_recently_used_archive_goes_first() {
        archive(1, 100, usedAt = 3_000)
        archive(2, 100, usedAt = 1_000)
        archive(3, 100, usedAt = 2_000)
        assertTrue(store.makeRoom(bookBytes = 150, capBytes = 400))
        assertFalse("oldest evicted", exists(2))
        assertTrue(exists(1))
        assertTrue(exists(3))
    }

    @Test fun touching_an_archive_saves_it_from_eviction() {
        archive(1, 100, usedAt = 1_000)
        archive(2, 100, usedAt = 2_000)
        now = 5_000
        store.touch(key(1))
        assertTrue(store.makeRoom(bookBytes = 150, capBytes = 300))
        assertTrue("used most recently, so kept", exists(1))
        assertFalse(exists(2))
    }

    @Test fun an_unfinished_directory_is_evicted_before_any_finished_one() {
        archive(1, 100, usedAt = 0)
        archive(2, 100, finished = false)
        assertTrue(store.makeRoom(bookBytes = 150, capBytes = 300))
        assertFalse(exists(2))
        assertTrue(exists(1))
    }

    @Test fun an_archive_a_source_has_open_is_never_evicted() {
        archive(1, 100, usedAt = 0)
        archive(2, 100, usedAt = 5)
        assertTrue(store.claim(key(1)))
        assertTrue(store.makeRoom(bookBytes = 150, capBytes = 300))
        assertTrue("claimed, so untouchable however old", exists(1))
        assertFalse(exists(2))
        store.release(key(1))
    }

    @Test fun room_that_cannot_be_made_is_reported_not_faked() {
        archive(1, 100)
        assertTrue(store.claim(key(1)))
        assertFalse(store.makeRoom(bookBytes = 500, capBytes = 400))
        assertTrue(exists(1))
        store.release(key(1))
        assertFalse("a book bigger than the cap never fits", store.makeRoom(bookBytes = 500, capBytes = 400))
    }

    @Test fun nothing_is_evicted_when_there_is_already_room() {
        archive(1, 100)
        assertTrue(store.makeRoom(bookBytes = 100, capBytes = 1_000))
        assertTrue(exists(1))
    }

    @Test fun total_bytes_counts_every_archive() {
        archive(1, 100)
        archive(2, 250, finished = false)
        assertTrue(store.totalBytes() >= 350)
    }

    @Test fun a_claim_is_exclusive_until_released() {
        assertTrue(store.claim(key(1)))
        assertFalse(store.claim(key(1)))
        store.release(key(1))
        assertTrue(store.claim(key(1)))
        store.release(key(1))
    }

    @Test fun the_marker_names_the_entry_count_it_was_written_for() {
        archive(1, 10, finished = false)
        assertFalse(store.isComplete(key(1), 1))
        store.markComplete(key(1), 7)
        assertTrue(store.isComplete(key(1), 7))
        assertFalse(store.isComplete(key(1), 8))
        assertEquals(setOf("00000.bin", "complete"), store.dir(key(1)).list()!!.toSet())
    }
}
