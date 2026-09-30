package com.absolutex.core.scan

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LibraryScannerReadFailureTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun scanFailure(listing: () -> Array<File>?): Pair<Int, List<ScannedBook>> = runBlocking {
        val unreadable = object : File(tmp.newFolder("unreadable").path) {
            override fun listFiles(): Array<File>? = listing()
        }
        val book = tmp.newFile("Found.cbz")
        val root = object : File(tmp.root.path) {
            override fun listFiles(): Array<File> = arrayOf(unreadable, book)
        }
        var failures = 0
        val books = LibraryScanner(parallelism = 2).scan(listOf(root)) { failures++ }.toList()
        failures to books
    }

    @Test fun `a null directory listing reports once and keeps scanning other folders`() {
        val (failures, books) = scanFailure { null }
        assertEquals(1, failures)
        assertEquals(listOf("Found.cbz"), books.map { it.displayName })
    }

    @Test fun `a denied directory reports once and keeps scanning`() {
        val (failures, books) = scanFailure { throw SecurityException("must not be logged") }
        assertEquals(1, failures)
        assertEquals(listOf("Found.cbz"), books.map { it.displayName })
    }

    @Test fun `an IO failure reports once and keeps scanning`() {
        val (failures, books) = scanFailure { throw IOException("must not be logged") }
        assertEquals(1, failures)
        assertEquals(listOf("Found.cbz"), books.map { it.displayName })
    }

    @Test fun `a readable tree including an empty folder never reports failure`() = runBlocking {
        tmp.newFolder("empty")
        tmp.newFile("Found.cbz")
        var failures = 0
        val books = LibraryScanner(parallelism = 2).scan(listOf(tmp.root)) { failures++ }.toList()
        assertEquals(0, failures)
        assertEquals(listOf("Found.cbz"), books.map { it.displayName })
    }
}
