package com.absolutex.remote.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Copying a remote file to local storage: completeness, atomicity, cancellation and resume. */
class OfflineCopierTest {

    @get:Rule val temp = TemporaryFolder()

    private fun destination(name: String = "book.cbz"): File = File(temp.root, name)

    private fun partsOf(destination: File): List<File> =
        temp.root.listFiles().orEmpty().filter {
            it.name.startsWith("${destination.name}.") && it.name.endsWith(".part")
        }

    // --- the ordinary copy ------------------------------------------------------------------

    @Test fun `a copy lands every byte, in order`() {
        val source = FakeSource(SIZE)
        val out = destination()
        val result = OfflineCopier(CHUNK).copy(source, out)

        assertTrue("got $result", result is OfflineCopyResult.Complete)
        assertEquals(SIZE, out.length())
        assertArrayEquals(expectedBytes(SIZE.toInt()), out.readBytes())
    }

    @Test fun `a file smaller than one chunk still copies whole`() {
        val small = 10L
        val out = destination("tiny.cbz")
        OfflineCopier(CHUNK).copy(FakeSource(small), out)
        assertArrayEquals(expectedBytes(small.toInt()), out.readBytes())
    }

    @Test fun `an empty file copies to an empty file rather than failing`() {
        val out = destination("empty.cbz")
        val result = OfflineCopier(CHUNK).copy(FakeSource(0), out)
        assertTrue("got $result", result is OfflineCopyResult.Complete)
        assertEquals(0L, out.length())
    }

    @Test fun `copying again transfers nothing once the file is already there`() {
        val out = destination()
        OfflineCopier(CHUNK).copy(FakeSource(SIZE), out)
        // ForbiddenSource throws on any read, so this passing means no byte moved — a weaker
        // "read fewer bytes" assertion would still pass if it re-copied a little.
        val again = OfflineCopier(CHUNK).copy(ForbiddenSource(SIZE), out)
        assertTrue("got $again", again is OfflineCopyResult.Complete)
    }

    // --- a partial copy must never look like a whole one --------------------------------------

    @Test fun `the destination does not exist until the copy is complete`() {
        val out = destination()
        var seenEarly = false
        OfflineCopier(CHUNK).copy(FakeSource(SIZE), out, onProgress = { copied, total ->
            // Checked at every chunk boundary: a copy that wrote straight to the destination
            // would be visible here, half-written, and a reader could open it as a truncated
            // archive. It must appear only at the final rename.
            if (copied < total && out.exists()) seenEarly = true
        })
        assertFalse("a partial copy was visible at the destination path", seenEarly)
        assertEquals(SIZE, out.length())
    }

    @Test fun `cancelling keeps what was copied and leaves the destination absent`() {
        val out = destination()
        val source = FakeSource(SIZE)
        var chunks = 0
        val result = OfflineCopier(CHUNK).copy(source, out, cancelled = { chunks++ > 2 })

        assertTrue("got $result", result is OfflineCopyResult.Cancelled)
        val cancelled = result as OfflineCopyResult.Cancelled
        assertTrue("nothing was copied at all", cancelled.copiedBytes > 0)
        assertTrue("everything was copied, so this proves nothing", cancelled.copiedBytes < SIZE)
        assertFalse("a cancelled copy must not publish", out.exists())
        assertEquals("the part must survive for a resume", 1, partsOf(out).size)
    }

    // --- resume --------------------------------------------------------------------------------

    @Test fun `a resumed copy continues rather than starting over, and is still correct`() {
        val out = destination()
        var chunks = 0
        val first = OfflineCopier(CHUNK).copy(FakeSource(SIZE), out, cancelled = { chunks++ > 2 })
        val alreadyDone = (first as OfflineCopyResult.Cancelled).copiedBytes

        val second = FakeSource(SIZE)
        val result = OfflineCopier(CHUNK).copy(second, out)

        assertTrue("got $result", result is OfflineCopyResult.Complete)
        // The teeth: a resume that silently restarted would read the whole file again and
        // still produce correct bytes, so correctness alone cannot tell the two apart.
        assertEquals("resumed from the wrong place", SIZE - alreadyDone, second.bytesRead)
        assertArrayEquals(expectedBytes(SIZE.toInt()), out.readBytes())
        assertTrue("the part must be gone once published", partsOf(out).isEmpty())
    }

    @Test fun `a part aimed at a different size is discarded, not spliced into`() {
        val out = destination()
        // A leftover from when the remote file was a different length. Resuming into it would
        // join bytes from two different files and produce a plausible, corrupt archive.
        val stale = File(temp.root, "${out.name}.${SIZE + 999}.part")
        stale.writeBytes(ByteArray(2048) { 0xEE.toByte() })

        val result = OfflineCopier(CHUNK).copy(FakeSource(SIZE), out)

        assertTrue("got $result", result is OfflineCopyResult.Complete)
        assertArrayEquals(expectedBytes(SIZE.toInt()), out.readBytes())
        assertFalse("the stale part must not survive", stale.exists())
    }

    @Test fun `a part longer than the file is discarded, never truncated and published`() {
        val out = destination()
        val part = File(temp.root, "${out.name}.$SIZE.part")
        part.writeBytes(ByteArray((SIZE + 100).toInt()) { 0xEE.toByte() })

        OfflineCopier(CHUNK).copy(FakeSource(SIZE), out)
        // Truncating the oversized part back to SIZE would publish its filler bytes as the
        // book. Only a full re-copy gives the real content.
        assertArrayEquals(expectedBytes(SIZE.toInt()), out.readBytes())
    }

    // --- progress and failure --------------------------------------------------------------

    @Test fun `progress never goes backwards and ends at the total`() {
        val seen = mutableListOf<Long>()
        val out = destination()
        OfflineCopier(CHUNK).copy(FakeSource(SIZE), out, onProgress = { copied, _ -> seen += copied })

        assertEquals("progress must end at the total", SIZE, seen.last())
        assertEquals("progress must start from what is already there", 0L, seen.first())
        assertEquals("progress went backwards: $seen", seen.sorted(), seen)
    }

    @Test(timeout = HANG_GUARD_MS)
    fun `a source that shrinks mid-copy fails instead of publishing a short file`() {
        val out = destination()
        // Reports SIZE, delivers less: publishing this would leave a truncated archive that
        // looks complete for ever after.
        val liar = object : com.absolutex.remote.core.RangeTransport {
            override fun sizeBytes(): Long = SIZE
            override fun readAt(offset: Long, length: Int): ByteArray =
                if (offset == 0L) ByteArray(length) else ByteArray(0)
        }
        try {
            OfflineCopier(CHUNK).copy(liar, out)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue("unhelpful message: ${expected.message}", expected.message?.contains("of") == true)
        }
        assertFalse("nothing may be published", out.exists())
    }

    private companion object {
        const val SIZE = 9_000L

        /** Small, so a few-thousand-byte fixture still exercises many chunk boundaries. */
        const val CHUNK = 1024

        /** A copier that stops advancing must fail the suite, not stall it. */
        const val HANG_GUARD_MS = 5_000L
    }
}
