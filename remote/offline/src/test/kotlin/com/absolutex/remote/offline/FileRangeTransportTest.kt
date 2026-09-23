package com.absolutex.remote.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reading an offline copy back: a local file standing in for a remote one. */
class FileRangeTransportTest {

    @get:Rule val temp = TemporaryFolder()

    private fun fileOf(size: Int): File =
        File(temp.root, "copy.cbz").apply { writeBytes(expectedBytes(size)) }

    @Test fun `size is the file's size`() {
        FileRangeTransport(fileOf(SIZE)).use { assertEquals(SIZE.toLong(), it.sizeBytes()) }
    }

    @Test fun `a range comes back exactly, from the right offset`() {
        FileRangeTransport(fileOf(SIZE)).use { transport ->
            assertArrayEquals(expectedBytes(100, from = 500), transport.readAt(500, 100))
        }
    }

    @Test fun `the last byte is readable, so the archive tail is reachable`() {
        // ZIP parsing starts from the end, so an off-by-one here would break every book.
        FileRangeTransport(fileOf(SIZE)).use { transport ->
            assertArrayEquals(expectedBytes(1, from = (SIZE - 1).toLong()), transport.readAt(SIZE - 1L, 1))
        }
    }

    @Test fun `a zero-length read is empty rather than an error`() {
        FileRangeTransport(fileOf(SIZE)).use { assertEquals(0, it.readAt(0, 0).size) }
    }

    @Test fun `reading past the end fails instead of returning a short buffer`() {
        FileRangeTransport(fileOf(SIZE)).use { transport ->
            try {
                transport.readAt(SIZE - 10L, 100)
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue("unhelpful message: ${expected.message}", expected.message?.contains("short read") == true)
            }
        }
    }

    @Test fun `a negative offset is a caller bug, not an IO failure`() {
        FileRangeTransport(fileOf(SIZE)).use { transport ->
            try {
                transport.readAt(-1, 10)
                fail("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("negative") == true)
            }
        }
    }

    @Test fun `concurrent reads do not interfere, because position is never shared`() {
        // The interface promises pread semantics so the decode pool can fan pages out. A
        // channel position shared between threads would make this flaky rather than wrong,
        // which is the worst way for it to be wrong.
        FileRangeTransport(fileOf(SIZE)).use { transport ->
            val pool = Executors.newFixedThreadPool(THREADS)
            try {
                val work = (0 until THREADS).map { worker ->
                    Callable {
                        val offset = (worker * CHUNK).toLong()
                        repeat(ROUNDS) {
                            assertArrayEquals(expectedBytes(CHUNK, offset), transport.readAt(offset, CHUNK))
                        }
                    }
                }
                pool.invokeAll(work).forEach { it.get() }
            } finally {
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    private companion object {
        const val SIZE = 4096
        const val THREADS = 8
        const val CHUNK = 256
        const val ROUNDS = 50
    }
}
