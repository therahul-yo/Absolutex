package com.absolutex.source.libarchive

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real corpus through the real JNI bridge on real ART.
 *
 * The host harness already proved the libarchive call sequence; this proves the parts the host
 * cannot: System.loadLibrary resolving on Android, JNI array marshalling under ART, and
 * ParcelFileDescriptor fds behaving the way the native side assumes.
 *
 * Corpus path comes from an instrumentation argument so no comic is committed to the repo:
 *   -Pandroid.testInstrumentationRunnerArguments.corpusDir=/sdcard/Download/absolutex-corpus
 */
@RunWith(AndroidJUnit4::class)
class LibArchiveSourceTest {

    // The app's own external files dir: readable with no permission at all, so this works
    // unchanged under scoped storage and on CI. /sdcard/Download is NOT readable here (EACCES).
    private val corpusDir: File by lazy {
        InstrumentationRegistry.getArguments().getString("corpusDir")?.let { return@lazy File(it) }
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
    }

    private fun open(name: String): LibArchiveSource {
        val f = File(corpusDir, name)
        assumeTrue("corpus file missing: $f", f.exists())
        return LibArchiveSource.open {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    @Test fun opens_rar4_cbr_and_finds_every_page() {
        open("absolute-batman-001.cbr").use { src ->
            assertEquals("expected 45 pages", 45, src.pages.size)
            assertEquals(
                "Absolute Batman 001 (2024) 001.jpg",
                src.pages.first().entryName,
            )
            // Natural sort must put the scan group's ZZZZZ.jpg trailer last, not first.
            assertEquals("ZZZZZ.jpg", src.pages.last().entryName)
        }
    }

    @Test fun first_page_decodes_to_real_jpeg_bytes() {
        open("absolute-batman-001.cbr").use { src ->
            val bytes = src.openPage(0).readBytes()
            assertTrue("page too small: ${bytes.size}", bytes.size > 100_000)
            assertEquals("JPEG SOI byte 0", 0xFF, bytes[0].toInt() and 0xFF)
            assertEquals("JPEG SOI byte 1", 0xD8, bytes[1].toInt() and 0xFF)
        }
    }

    @Test fun last_page_is_reachable_without_reading_the_whole_archive() {
        open("absolute-batman-001.cbr").use { src ->
            val last = src.pages.size - 1
            val bytes = src.openPage(last).readBytes()
            assertTrue(bytes.size > 1000)
            assertEquals(0xFF, bytes[0].toInt() and 0xFF)
            assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        }
    }

    @Test fun second_book_opens_with_its_own_page_count() {
        open("dc-all-in-001.cbr").use { src ->
            assertEquals(55, src.pages.size)
        }
    }

    @Test fun concurrent_reads_return_identical_bytes_to_serial_reads() {
        open("absolute-batman-001.cbr").use { src ->
            val serial = (0 until 6).map { src.openPage(it).readBytes().size }
            val parallel = arrayOfNulls<Int>(6)
            val ts = (0 until 6).map { i ->
                Thread { parallel[i] = runCatching { src.openPage(i).readBytes().size }.getOrNull() }
            }
            ts.forEach { it.start() }; ts.forEach { it.join() }
            assertEquals("parallel reads disagree with serial reads", serial, parallel.toList())
        }
    }

    @Test fun concurrent_page_reads_are_safe() {
        // The bridge is stateless by design so the decode pool can fan out. Prove it.
        open("absolute-batman-001.cbr").use { src ->
            val n = 8
            val sizes = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            val threads = (0 until n).map { i ->
                Thread {
                    // Catch here: an uncaught throw on a bare Thread kills the whole test process.
                    runCatching { src.openPage(i % src.pages.size).readBytes().size }
                        .onSuccess { sizes.add(it) }
                        .onFailure { errors.add("page $i: ${it.message}") }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertTrue("concurrent reads failed: $errors", errors.isEmpty())
            assertEquals(n, sizes.size)
            assertTrue("a concurrent read returned too little: $sizes", sizes.all { it > 1000 })
        }
    }
}

/**
 * Archive-side cost of the §3 "tap book -> first page rendered < 250 ms" budget.
 * This measures index + extract only; decode and render are not included.
 */
@RunWith(AndroidJUnit4::class)
class LibArchiveTimingTest {

    private val dir by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
    }

    private fun pfd(name: String): () -> ParcelFileDescriptor = {
        ParcelFileDescriptor.open(File(dir, name), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Test fun cold_open_and_first_page_cost() {
        val f = File(dir, "absolute-batman-001.cbr")
        assumeTrue("corpus missing", f.exists())

        // Warm once so we measure steady state, not first-call JNI linkage.
        LibArchiveSource.open(pfd("absolute-batman-001.cbr")).use { it.openPage(0).readBytes() }

        val runs = 5
        val openNs = LongArray(runs)
        val pageNs = LongArray(runs)
        repeat(runs) { i ->
            var t = System.nanoTime()
            val src = LibArchiveSource.open(pfd("absolute-batman-001.cbr"))
            openNs[i] = System.nanoTime() - t
            t = System.nanoTime()
            src.openPage(0).readBytes()
            pageNs[i] = System.nanoTime() - t
            src.close()
        }
        val openMs = openNs.map { it / 1_000_000.0 }.sorted()
        val pageMs = pageNs.map { it / 1_000_000.0 }.sorted()

        // Also time the worst case: the LAST page, which walks every entry header.
        val src = LibArchiveSource.open(pfd("absolute-batman-001.cbr"))
        val t0 = System.nanoTime()
        src.openPage(src.pages.size - 1).readBytes()
        val lastMs = (System.nanoTime() - t0) / 1_000_000.0
        src.close()

        println("ABSOLUTEX_TIMING index_median_ms=%.1f page0_median_ms=%.1f lastpage_ms=%.1f"
            .format(openMs[runs / 2], pageMs[runs / 2], lastMs))

        assertTrue("index+first page must leave room inside the 250 ms budget",
            openMs[runs / 2] + pageMs[runs / 2] < 250.0)
    }
}
