package com.absolutex.core.media

import com.absolutex.core.scan.LibraryChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping/debounce core on plain JVM: no Robolectric, no device. These assert the
 * decisions; [MediaStoreObserverTest] asserts the shell around them.
 */
class MediaChangeClassifierTest {

    @Test fun `container maps to Added`() {
        assertEquals(
            LibraryChange.Added("/storage/emulated/0/Download/Batman 001.cbz"),
            MediaChangeClassifier.classifyRow("/storage/emulated/0/Download/Batman 001.cbz", false),
        )
    }

    @Test fun `every scanner container extension maps to Added`() {
        val extensions = listOf("cbz", "cbr", "cb7", "cbt", "zip", "rar", "7z", "tar", "pdf")
        for (ext in extensions) {
            val path = "/storage/emulated/0/Download/book.$ext"
            assertEquals(LibraryChange.Added(path), MediaChangeClassifier.classifyRow(path, false))
        }
    }

    @Test fun `extension match is case-insensitive`() {
        val path = "/storage/emulated/0/Download/Series V01.PDF"
        assertEquals(LibraryChange.Added(path), MediaChangeClassifier.classifyRow(path, false))
    }

    @Test fun `non-book media returns null`() {
        assertNull(MediaChangeClassifier.classifyRow("/storage/emulated/0/Download/notes.txt", false))
    }

    @Test fun `extensionless file returns null`() {
        assertNull(MediaChangeClassifier.classifyRow("/storage/emulated/0/Download/README", false))
    }

    @Test fun `junk filenames never map`() {
        assertNull(MediaChangeClassifier.classifyRow("/storage/emulated/0/Download/Thumbs.db", false))
        assertNull(MediaChangeClassifier.classifyRow("/storage/emulated/0/Download/.DS_Store", false))
    }

    @Test fun `junk directory contents never map`() {
        val junked = "/storage/emulated/0/Download/__MACOSX/001.cbz"
        assertNull(MediaChangeClassifier.classifyRow(junked, false))
        assertNull(MediaChangeClassifier.classifyRow(junked, true))
        val junkDir = "/storage/emulated/0/Download/__MACOSX"
        assertNull(
            MediaChangeClassifier.classifyFolder(junkDir, FolderStats(5, false), false, false),
        )
    }

    @Test fun `hidden ancestor skipped unless hidden included`() {
        val path = "/storage/emulated/0/Download/.hidden/book.cbz"
        assertNull(MediaChangeClassifier.classifyRow(path, false))
        assertEquals(LibraryChange.Added(path), MediaChangeClassifier.classifyRow(path, true))
    }

    @Test fun `loose image defers to folder evaluation`() {
        val path = "/storage/emulated/0/Pictures/Loose/001.jpg"
        assertNull(MediaChangeClassifier.classifyRow(path, false))
        assertTrue(MediaChangeClassifier.needsFolderEvaluation(path, false))
    }

    @Test fun `non-page non-container needs no folder evaluation`() {
        assertFalse(
            MediaChangeClassifier.needsFolderEvaluation("/storage/emulated/0/Download/notes.txt", false),
        )
    }

    @Test fun `folder crossing the image threshold promotes`() {
        assertEquals(
            LibraryChange.FolderPromoted("/d/Loose", 3),
            MediaChangeClassifier.classifyFolder("/d/Loose", FolderStats(3, false), false, false),
        )
    }

    @Test fun `folder below threshold is not a book`() {
        assertNull(
            MediaChangeClassifier.classifyFolder("/d/Loose", FolderStats(1, false), false, false),
        )
    }

    @Test fun `folder with visible subdirs is not a book`() {
        assertNull(
            MediaChangeClassifier.classifyFolder("/d/Nest", FolderStats(9, true), false, false),
        )
    }

    @Test fun `known folder book reports modification`() {
        assertEquals(
            LibraryChange.Modified("/d/Loose"),
            MediaChangeClassifier.classifyFolder("/d/Loose", FolderStats(4, false), true, false),
        )
    }

    @Test fun `folder losing book status reports removal`() {
        assertEquals(
            LibraryChange.Removed("/d/Loose"),
            MediaChangeClassifier.classifyFolder("/d/Loose", FolderStats(1, false), true, false),
        )
    }

    @Test fun `self-export matches the export dir by segment`() {
        assertTrue(
            MediaChangeClassifier.isSelfExport("/storage/emulated/0/Pictures/Absolutex/page01.png"),
        )
        assertFalse(
            MediaChangeClassifier.isSelfExport("/storage/emulated/0/Pictures/Absolutex2/page01.png"),
        )
        assertFalse(
            MediaChangeClassifier.isSelfExport("/storage/emulated/0/Download/book.cbz"),
        )
    }

    @Test fun `coalesce dedupes and bounds a burst`() {
        val burst = List(600) { it.toLong() } + List(100) { 7L }
        val coalesced = MediaChangeClassifier.coalesce(burst)
        assertEquals(MediaChangeClassifier.MAX_IDS_PER_FLUSH, coalesced.size)
        assertEquals(MediaChangeClassifier.MAX_IDS_PER_FLUSH, coalesced.distinct().size)
        assertTrue(MediaChangeClassifier.overflowed(burst))
        assertFalse(MediaChangeClassifier.overflowed(listOf(1L, 2L, 2L)))
    }
}
