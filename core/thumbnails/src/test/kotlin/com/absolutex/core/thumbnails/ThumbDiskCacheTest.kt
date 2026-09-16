package com.absolutex.core.thumbnails

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThumbDiskCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun cache(maxBytes: Long = ThumbDiskCache.DISK_CAP_BYTES) =
        ThumbDiskCache(File(tmp.root, "thumbs"), maxBytes)

    private fun bytes(size: Int, seed: Int = 0): ByteArray =
        ByteArray(size) { ((it + seed) % 251).toByte() }

    @Test fun `put then get round-trips bytes`() {
        val disk = cache()
        disk.put("aa", bytes(64))
        assertArrayEquals(bytes(64), disk.get("aa"))
    }

    @Test fun `missing keys read as null`() {
        assertNull(cache().get("missing"))
    }

    @Test fun `entries survive a new instance over the same dir`() {
        val dir = File(tmp.root, "thumbs")
        ThumbDiskCache(dir).put("aa", bytes(64))
        assertArrayEquals(bytes(64), ThumbDiskCache(dir).get("aa"))
    }

    @Test fun `eviction keeps the total under a tiny cap, oldest first`() {
        val disk = cache(maxBytes = 100L)
        disk.put("a", bytes(40))
        disk.put("b", bytes(40))
        disk.put("c", bytes(40))
        assertTrue(disk.sizeBytes() <= 100L)
        assertNull(disk.get("a"))
        assertArrayEquals(bytes(40, seed = 0), disk.get("c"))
    }

    @Test fun `a truncated file reads as a miss and is evicted`() {
        val disk = cache()
        disk.put("aa", bytes(64))
        File(tmp.root, "thumbs/aa${ThumbKeys.FILE_SUFFIX}").writeBytes(bytes(10))
        assertNull(disk.get("aa"))
        assertEquals(0L, disk.sizeBytes())
        assertEquals(0, disk.entryCount())
    }

    @Test fun `a vanished file reads as a miss and is evicted`() {
        val disk = cache()
        disk.put("aa", bytes(64))
        assertTrue(File(tmp.root, "thumbs/aa${ThumbKeys.FILE_SUFFIX}").delete())
        assertNull(disk.get("aa"))
        assertEquals(0, disk.entryCount())
    }

    @Test fun `a corrupt journal rebuilds instead of crashing`() {
        val dir = File(tmp.root, "thumbs")
        ThumbDiskCache(dir).put("aa", bytes(64))
        File(dir, ThumbJournal.JOURNAL_FILE).writeText("half-written {{{")
        val rebuilt = ThumbDiskCache(dir)
        assertNull(rebuilt.get("aa"))
        rebuilt.put("bb", bytes(8))
        assertArrayEquals(bytes(8), rebuilt.get("bb"))
    }

    @Test fun `an unknown journal version wipes and rebuilds`() {
        val dir = File(tmp.root, "thumbs")
        ThumbDiskCache(dir).put("aa", bytes(64))
        File(dir, ThumbJournal.JOURNAL_FILE).writeText("{\"version\":999,\"entries\":[]}")
        val rebuilt = ThumbDiskCache(dir)
        assertEquals(0, rebuilt.entryCount())
        assertEquals(0L, rebuilt.sizeBytes())
        assertFalse(File(dir, "aa${ThumbKeys.FILE_SUFFIX}").exists())
    }

    @Test fun `a pre-bump v1 journal from the PNG era wipes and rebuilds as WEBP`() {
        // JOURNAL_VERSION moved to 2 with the PNG to WEBP_LOSSY switch, so a journal a pre-bump
        // build wrote (hardcoded here, not via the constant) must be treated the same as any other
        // unreadable journal: wiped, along with the stale PNG bytes it was accounting for.
        val dir = File(tmp.root, "thumbs")
        dir.mkdirs()
        File(dir, ThumbJournal.JOURNAL_FILE).writeText("{\"version\":1,\"entries\":[{\"k\":\"aa\",\"s\":64}]}")
        File(dir, "aa${ThumbKeys.FILE_SUFFIX}").writeBytes(bytes(64))
        val rebuilt = ThumbDiskCache(dir)
        assertEquals(0, rebuilt.entryCount())
        assertEquals(0L, rebuilt.sizeBytes())
        assertNull(rebuilt.get("aa"))
        assertFalse(File(dir, "aa${ThumbKeys.FILE_SUFFIX}").exists())
    }

    @Test fun `a missing journal adopts orphaned entry files`() {
        val dir = File(tmp.root, "thumbs")
        ThumbDiskCache(dir).put("aa", bytes(64))
        assertTrue(File(dir, ThumbJournal.JOURNAL_FILE).delete())
        assertArrayEquals(bytes(64), ThumbDiskCache(dir).get("aa"))
    }

    @Test fun `puts leave no temp files behind`() {
        val disk = cache()
        disk.put("aa", bytes(64))
        val leftovers = tmp.root.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
        assertTrue(leftovers.isEmpty())
        assertEquals(1, disk.entryCount())
    }

    @Test fun `clear drops entries and files`() {
        val disk = cache()
        disk.put("aa", bytes(64))
        disk.clear()
        assertNull(disk.get("aa"))
        assertEquals(0L, disk.sizeBytes())
        assertEquals(0, disk.entryCount())
    }

    @Test fun `remove drops one entry and keeps the rest`() {
        val disk = cache()
        disk.put("aa", bytes(16))
        disk.put("bb", bytes(16))
        disk.remove("aa")
        assertNull(disk.get("aa"))
        assertArrayEquals(bytes(16), disk.get("bb"))
        assertEquals(16L, disk.sizeBytes())
    }

    @Test fun `the journal is batched, not rewritten on every put, and close flushes it`() {
        // A single put stays well under PERSIST_EVERY_PUTS, so the on-disk journal is still the
        // empty one the constructor wrote, until close() forces the batched state out.
        val dir = File(tmp.root, "thumbs")
        val disk = ThumbDiskCache(dir)
        disk.put("aa", bytes(16))
        val onDiskBeforeClose = ThumbJournal.parse(File(dir, ThumbJournal.JOURNAL_FILE).readText())
        assertEquals(0, onDiskBeforeClose?.entryCount())
        disk.close()
        val onDiskAfterClose = ThumbJournal.parse(File(dir, ThumbJournal.JOURNAL_FILE).readText())
        assertEquals(1, onDiskAfterClose?.entryCount())
    }
}
