package com.absolutex.core.thumbnails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbJournalTest {

    @Test fun `empty journal accounts nothing`() {
        val journal = ThumbJournal.empty()
        assertEquals(0L, journal.totalBytes())
        assertEquals(0, journal.entryCount())
        assertNull(journal.sizeOf("missing"))
    }

    @Test fun `puts accumulate size accounting`() {
        val journal = ThumbJournal.empty()
        journal.put("a", 10L)
        journal.put("b", 20L)
        assertEquals(30L, journal.totalBytes())
        assertEquals(2, journal.entryCount())
        assertEquals(10L, journal.sizeOf("a"))
    }

    @Test fun `re-putting a key replaces its size instead of double counting`() {
        val journal = ThumbJournal.empty()
        journal.put("a", 10L)
        journal.put("a", 40L)
        assertEquals(40L, journal.totalBytes())
        assertEquals(1, journal.entryCount())
    }

    @Test fun `remove returns accounting to the caller`() {
        val journal = ThumbJournal.empty()
        journal.put("a", 10L)
        assertTrue(journal.remove("a"))
        assertFalse(journal.remove("a"))
        assertEquals(0L, journal.totalBytes())
        assertEquals(0, journal.entryCount())
    }

    @Test fun `eviction is oldest first`() {
        val journal = ThumbJournal.empty()
        journal.put("a", 10L)
        journal.put("b", 10L)
        journal.put("c", 10L)
        assertEquals(listOf("a"), journal.keysToEvict(25L))
        assertEquals(listOf("a", "b"), journal.keysToEvict(10L))
    }

    @Test fun `eviction leaves the total under the cap`() {
        val journal = ThumbJournal.empty()
        journal.put("a", 10L)
        journal.put("b", 10L)
        journal.put("c", 10L)
        val victims = journal.keysToEvict(15L)
        victims.forEach { journal.remove(it) }
        assertTrue(journal.totalBytes() <= 15L)
        assertEquals(1, journal.entryCount())
    }

    @Test fun `a single entry larger than the cap evicts itself`() {
        val journal = ThumbJournal.empty()
        journal.put("huge", 100L)
        assertEquals(listOf("huge"), journal.keysToEvict(10L))
    }

    @Test fun `nothing is evicted when already under the cap`() {
        val journal = ThumbJournal.empty()
        journal.put("a", 10L)
        assertTrue(journal.keysToEvict(10L).isEmpty())
        assertTrue(journal.keysToEvict(100L).isEmpty())
    }

    @Test fun `reading an entry refreshes its recency`() {
        val journal = ThumbJournal.empty()
        journal.put("a", 10L)
        journal.put("b", 10L)
        journal.sizeOf("a")
        assertEquals(listOf("b"), journal.keysToEvict(15L))
    }

    @Test fun `serialize then parse round-trips entries in order`() {
        val journal = ThumbJournal.empty()
        journal.put("a", 10L)
        journal.put("b", 20L)
        val parsed = ThumbJournal.parse(journal.serialize())!!
        assertEquals(30L, parsed.totalBytes())
        assertEquals(2, parsed.entryCount())
        assertEquals(10L, parsed.sizeOf("a"))
        assertEquals(20L, parsed.sizeOf("b"))
        assertEquals(journal.serialize(), parsed.serialize())
    }

    @Test fun `an empty entry list parses to an empty journal`() {
        val parsed = ThumbJournal.parse(ThumbJournal.empty().serialize())!!
        assertEquals(0L, parsed.totalBytes())
        assertEquals(0, parsed.entryCount())
    }

    @Test fun `corrupt text parses to null instead of throwing`() {
        assertNull(ThumbJournal.parse(""))
        assertNull(ThumbJournal.parse("not json"))
        assertNull(ThumbJournal.parse("{\"version\":1,\"entries\":["))
        assertNull(ThumbJournal.parse("{\"version\":1}"))
        assertNull(ThumbJournal.parse("{}"))
        assertNull(ThumbJournal.parse("[]"))
    }

    @Test fun `unknown versions parse to null so the caller rebuilds`() {
        assertNull(ThumbJournal.parse("{\"version\":999,\"entries\":[]}"))
        assertNull(ThumbJournal.parse("{\"version\":0,\"entries\":[]}"))
    }

    @Test fun `negative sizes parse to null`() {
        assertNull(ThumbJournal.parse("{\"version\":2,\"entries\":[{\"k\":\"a\",\"s\":-1}]}"))
    }

    @Test fun `the journal version is pinned at two`() {
        assertEquals(2, ThumbJournal.JOURNAL_VERSION)
    }

    @Test fun `unknown fields fail closed so the caller rebuilds`() {
        // Evolution is version-gated, not field-tolerant: any shape change bumps the version.
        assertNull(ThumbJournal.parse("{\"version\":2,\"extra\":true,\"entries\":[]}"))
        assertNull(ThumbJournal.parse("{\"version\":2,\"entries\":[{\"k\":\"a\",\"s\":1,\"x\":2}]}"))
    }

    @Test fun `a pre-bump v1 journal is rejected so old PNG-era entries rebuild as WEBP`() {
        // Simulates an app upgrade: bytes a prior build wrote at JOURNAL_VERSION 1 must fail closed
        // now, not be read as if they were version 2, since the disk format itself changed underneath.
        assertNull(ThumbJournal.parse("{\"version\":1,\"entries\":[{\"k\":\"aa\",\"s\":64}]}"))
    }
}
