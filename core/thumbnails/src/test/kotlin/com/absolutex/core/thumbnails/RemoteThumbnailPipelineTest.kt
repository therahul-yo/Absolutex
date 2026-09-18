package com.absolutex.core.thumbnails

import com.absolutex.remote.core.CoreComicSource
import com.absolutex.remote.core.RangeTransport
import com.absolutex.remote.core.RemoteOpenResult
import com.absolutex.remote.core.encodeRemoteUri
import com.absolutex.source.ComicSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The remote sourceId story, end to end: a [CoreComicSource] over a ranged transport loads
 * through [ThumbnailPipeline] with zero pipeline changes, keyed by the same
 * `absolutex-remote://` Uri the browse UI mints.
 *
 * Keys are SHA-256 hex of the Uri string, so slashes, `%XX` escapes, `+` and non-ASCII names
 * can never smuggle in a separator or a traversal — the filesystem only ever sees hex. Two
 * servers sharing a path still isolate because the server id is part of the hashed material.
 * Hostile archives fail the load as IOException and are never cached, so a retry re-reads.
 *
 * Native graphics mode is required, not cosmetic: the legacy shadow's Bitmap.compress writes
 * PNG bytes for every format, which would hide a WEBP regression (see ThumbnailPipelineTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteThumbnailPipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    /** In-memory [RangeTransport] with call recording, mirroring remote/core's own fake. */
    private class RemoteBytes(private val bytes: ByteArray) : RangeTransport {
        var reads = 0
            private set

        override fun sizeBytes(): Long = bytes.size.toLong()

        override fun readAt(offset: Long, length: Int): ByteArray {
            if (offset + length > bytes.size) throw IOException("read past end")
            reads++
            return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
        }

        override fun close() = Unit
    }

    private fun cbz(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun remoteSource(bytes: ByteArray, name: String = "book.cbz"): CoreComicSource {
        val opened = CoreComicSource.open(RemoteBytes(bytes), name)
        assertTrue(opened is RemoteOpenResult.Ready)
        return (opened as RemoteOpenResult.Ready).source as CoreComicSource
    }

    private fun pipeline(dir: String = "thumbs") =
        ThumbnailPipeline(File(tmp.root, dir), 4L * 1024 * 1024)

    private fun diskEntries(dir: String = "thumbs"): Int =
        File(tmp.root, dir).listFiles()?.count { it.extension == "thumb" } ?: 0

    @Test fun `pipeline loads a remote-backed source with no pipeline changes`() = runTest {
        val cover = testPngBytes(64, 32)
        val source = remoteSource(cbz("cover.jpg" to cover, "page02.jpg" to testPngBytes(32, 16)))
        val uri = encodeRemoteUri("nas", "/comics/batman + robin.cbz")
        val bitmap = pipeline().load(source, ThumbRequest(uri, 0, ThumbRequest.BUCKET_SMALL))
        assertEquals(ThumbRequest.BUCKET_SMALL, bitmap.width)
    }

    @Test fun `two servers sharing a path isolate their cache entries`() = runTest {
        val bytes = cbz("cover.jpg" to testPngBytes(64, 32))
        val path = "/comics/shared.cbz"
        val first = ThumbRequest(encodeRemoteUri("nas-a", path), 0, ThumbRequest.BUCKET_SMALL)
        val second = ThumbRequest(encodeRemoteUri("nas-b", path), 0, ThumbRequest.BUCKET_SMALL)
        assertNotEquals(ThumbKeys.keyHex(first), ThumbKeys.keyHex(second))
        val thumbs = pipeline()
        thumbs.load(remoteSource(bytes), first)
        thumbs.load(remoteSource(bytes), second)
        assertEquals(2, diskEntries())
    }

    @Test fun `hostile Uri characters are safe distinct keys`() {
        val uris = listOf(
            encodeRemoteUri("nas", "/Batman + Robin.cbz"),
            encodeRemoteUri("nas", "/comics/my book/v2.cbz"),
            encodeRemoteUri("nas", "/100% legit.cbz"),
            encodeRemoteUri("nas", "/\u6F2B\u753B01.cbz"),
            encodeRemoteUri("nas", "/comics/nested/deep/book.cbz"),
        )
        val names = uris.map { ThumbKeys.fileName(ThumbRequest(it, 0, ThumbRequest.BUCKET_SMALL)) }
        for (name in names) {
            assertTrue(name, name.matches(Regex("[0-9a-f]+\\.thumb")))
        }
        assertEquals(uris.size, names.toSet().size)
    }

    @Test fun `empty remote archive fails the load like an empty local source`() = runTest {
        // A valid ZIP whose only entry is junk: indexing succeeds with zero pages, so the
        // pipeline's empty-book guard throws IOException — the same verdict an empty local
        // source gets, and nothing is cached either way.
        val bytes = cbz("Thumbs.db" to ByteArray(8))
        val uri = encodeRemoteUri("nas", "/comics/empty.cbz")
        val request = ThumbRequest(uri, 0, ThumbRequest.BUCKET_SMALL)
        val thumbs = pipeline()
        val source = remoteSource(bytes)
        repeat(2) {
            val failure = runCatching { thumbs.load(source, request) }.exceptionOrNull()
            assertTrue("expected IOException, got $failure", failure is IOException)
        }
        assertEquals(0, diskEntries())
    }

    @Test fun `giant first entry fails the load and never caches`() = runTest {
        // The directory declares a 64 MiB compressed first entry: the load must fail on the
        // numbers before its bytes can grow anywhere near that, with nothing cached.
        val bytes = cbz("cover.jpg" to testPngBytes(16, 8), "page02.jpg" to testPngBytes(16, 8))
        val patched = bytes.copyOf()
        val central = u32(patched, eocdStart(patched) + EOCD_CD_OFFSET).toInt()
        le32(patched, central + CENTRAL_COMP_SIZE, GIANT_ENTRY_BYTES)
        val uri = encodeRemoteUri("nas", "/comics/bomb.cbz")
        val request = ThumbRequest(uri, 0, ThumbRequest.BUCKET_SMALL)
        val thumbs = pipeline()
        repeat(2) {
            try {
                thumbs.load(remoteSource(patched), request)
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("too large") == true)
            }
        }
        assertEquals(0, diskEntries())
    }

    @Test fun `corrupt remote page fails the load and a retry re-reads the source`() = runTest {
        // Zeroes inside the first entry's DEFLATE payload: the directory still parses, but
        // inflation dies — every attempt re-opens the page on the real source, proving the
        // failure was never cached on any layer. (Reads are counted at the source, not the
        // transport: the block cache legitimately absorbs repeat range reads of one book.)
        val good = cbz("cover.jpg" to testPngBytes(64, 32))
        val bad = good.copyOf()
        val dataStart = localDataStart(bad)
        for (i in 0 until CORRUPT_BYTES) {
            bad[dataStart + CORRUPT_OFFSET + i] = 0
        }
        val opened = CoreComicSource.open(RemoteBytes(bad), "book.cbz")
        assertTrue(opened is RemoteOpenResult.Ready)
        val source = CountingSource((opened as RemoteOpenResult.Ready).source as CoreComicSource)
        val request = ThumbRequest(encodeRemoteUri("nas", "/comics/corrupt.cbz"), 0, ThumbRequest.BUCKET_SMALL)
        val thumbs = pipeline()
        val first = runCatching { thumbs.load(source, request) }.exceptionOrNull()
        assertTrue("expected IOException, got $first", first is IOException)
        val second = runCatching { thumbs.load(source, request) }.exceptionOrNull()
        assertTrue("expected IOException, got $second", second is IOException)
        assertEquals(2, source.opens)
        assertEquals(0, diskEntries())
    }

    /** Real source with an openPage counter, proving retries re-run the fetch. */
    private class CountingSource(private val delegate: CoreComicSource) : ComicSource by delegate {
        var opens = 0
            private set

        override fun openPage(index: Int): InputStream {
            opens++
            return delegate.openPage(index)
        }
    }

    /** Start of the EOCD in a fixture with an empty comment. */
    private fun eocdStart(bytes: ByteArray): Int = bytes.size - EOCD_SIZE

    /** Offset of the first entry's data: 30-byte local header plus name plus extra. */
    private fun localDataStart(bytes: ByteArray): Int {
        val nameLen = u16(bytes, LOCAL_NAME_LEN)
        val extraLen = u16(bytes, LOCAL_EXTRA_LEN)
        return LOCAL_HEADER_SIZE + nameLen + extraLen
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and BYTE_MASK) or ((bytes[at + 1].toInt() and BYTE_MASK) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toInt() and BYTE_MASK).toLong() or
            ((bytes[at + 1].toInt() and BYTE_MASK).toLong() shl 8) or
            ((bytes[at + 2].toInt() and BYTE_MASK).toLong() shl 16) or
            ((bytes[at + 3].toInt() and BYTE_MASK).toLong() shl 24)

    private fun le32(bytes: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) {
            bytes[at + i] = (v shr (8 * i)).toByte()
        }
    }

    private companion object {
        const val EOCD_SIZE = 22
        const val EOCD_CD_OFFSET = 16
        const val CENTRAL_COMP_SIZE = 20
        const val GIANT_ENTRY_BYTES = 64L * 1024 * 1024
        const val BYTE_MASK = 0xFF
        const val LOCAL_HEADER_SIZE = 30
        const val LOCAL_NAME_LEN = 26
        const val LOCAL_EXTRA_LEN = 28
        const val CORRUPT_OFFSET = 20
        const val CORRUPT_BYTES = 8
    }
}
