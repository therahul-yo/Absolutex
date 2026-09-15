package com.absolutex.remote.ftp

import java.io.IOException
import java.util.zip.ZipEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtpZipTest {

    private val first = ZipFixtures.pageBytes(7, 3000)
    private val second = ZipFixtures.pageBytes(9, 5000)
    private val padding = ByteArray(600_000) { 0x5A.toByte() }
    private val path = "/b.cbz"

    private fun archive(): ByteArray = ZipFixtures.build(
        listOf(
            Triple("p02.jpg", ZipEntry.DEFLATED, second),
            Triple("p01.jpg", ZipEntry.STORED, first),
            Triple("padding.bin", ZipEntry.STORED, padding),
        ),
    )

    private fun patchU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
    }

    private fun patchU32(bytes: ByteArray, offset: Int, value: Long) {
        patchU16(bytes, offset, (value and 0xFFFF).toInt())
        patchU16(bytes, offset + 2, ((value shr 16) and 0xFFFF).toInt())
    }

    private fun findSig(bytes: ByteArray, sig: Int): Int {
        var cursor = 0
        while (cursor + 3 < bytes.size) {
            val value = (bytes[cursor].toInt() and 0xFF) or
                ((bytes[cursor + 1].toInt() and 0xFF) shl 8) or
                ((bytes[cursor + 2].toInt() and 0xFF) shl 16) or
                ((bytes[cursor + 3].toInt() and 0xFF) shl 24)
            if (value == sig) return cursor
            cursor++
        }
        return -1
    }

    @Test fun `pages list in natural order with sizes`() {
        val source = FtpZipSource.open(FakeFtpTransport(archive()), path)
        assertEquals(listOf("p01.jpg", "p02.jpg"), source.pages.map { it.entryName })
        assertEquals(3000L, source.pages[0].sizeBytes)
        assertEquals(5000L, source.pages[1].sizeBytes)
        source.close()
    }

    @Test fun `stored and deflated pages round-trip exactly`() {
        val source = FtpZipSource.open(FakeFtpTransport(archive()), path)
        source.openPage(0).use { assertArrayEquals(first, it.readBytes()) }
        source.openPage(1).use { assertArrayEquals(second, it.readBytes()) }
        source.close()
    }

    @Test fun `streaming pulls less than a quarter of the file`() {
        val bytes = archive()
        val fake = FakeFtpTransport(bytes)
        val source = FtpZipSource.open(fake, path)
        source.openPage(0).use { it.readBytes() }
        source.openPage(1).use { it.readBytes() }
        source.close()
        assertTrue("fetched ${fake.bytesServed} of ${bytes.size}", fake.bytesServed < bytes.size / 4)
    }

    @Test fun `cover equals the first page within a bounded cost`() {
        val bytes = archive()
        val fake = FakeFtpTransport(bytes)
        val source = FtpZipSource.open(fake, path)
        source.openCover().use { assertArrayEquals(first, it.readBytes()) }
        source.close()
        assertTrue("fetched ${fake.bytesServed} of ${bytes.size}", fake.bytesServed < bytes.size / 4)
    }

    @Test fun `junk entries never become pages`() {
        val bytes = ZipFixtures.build(
            listOf(
                Triple("__MACOSX/._p01.jpg", ZipEntry.STORED, first),
                Triple(".DS_Store", ZipEntry.STORED, first),
                Triple("thumbs.db", ZipEntry.STORED, first),
                Triple("ch1/", ZipEntry.STORED, ByteArray(0)),
                Triple("p01.jpg", ZipEntry.STORED, first),
            ),
        )
        val source = FtpZipSource.open(FakeFtpTransport(bytes), path)
        assertEquals(listOf("p01.jpg"), source.pages.map { it.entryName })
        source.close()
    }

    @Test fun `non-zip fails fast naming the cached fallback`() {
        val fake = FakeFtpTransport(ByteArray(1000) { 0x41.toByte() })
        var thrown: IOException? = null
        try {
            FtpZipSource.open(fake, path)
        } catch (expected: IOException) {
            thrown = expected
        }
        val message = requireNotNull(requireNotNull(thrown).message)
        assertTrue(message.contains("cached fallback"))
    }

    @Test fun `truncated archive degrades to IOException`() {
        val full = archive()
        val bytes = full.copyOf(full.size * 3 / 5)
        var thrown: IOException? = null
        try {
            FtpZipSource.open(FakeFtpTransport(bytes), path)
        } catch (expected: IOException) {
            thrown = expected
        }
        assertNotNull(thrown)
    }

    @Test fun `corrupt local header degrades to IOException`() {
        val bytes = ZipFixtures.build(
            listOf(
                Triple("p01.jpg", ZipEntry.STORED, first),
                Triple("padding.bin", ZipEntry.STORED, ByteArray(1024)),
            ),
        )
        bytes[0] = 0
        bytes[1] = 0
        bytes[2] = 0
        bytes[3] = 0
        val source = FtpZipSource.open(FakeFtpTransport(bytes), path)
        var thrown: IOException? = null
        try {
            source.openPage(0).use { it.readBytes() }
        } catch (expected: IOException) {
            thrown = expected
        } finally {
            source.close()
        }
        assertNotNull(thrown)
    }

    @Test fun `unknown method degrades to IOException`() {
        val bytes = ZipFixtures.build(
            listOf(
                Triple("p01.jpg", ZipEntry.STORED, first),
                Triple("padding.bin", ZipEntry.STORED, ByteArray(1024)),
            ),
        )
        val central = findSig(bytes, 0x02014B50)
        assertTrue(central >= 0)
        patchU16(bytes, central + 10, 12)
        val source = FtpZipSource.open(FakeFtpTransport(bytes), path)
        var thrown: IOException? = null
        try {
            source.openPage(0).use { it.readBytes() }
        } catch (expected: IOException) {
            thrown = expected
        } finally {
            source.close()
        }
        val message = requireNotNull(requireNotNull(thrown).message)
        assertTrue(message.contains("unsupported"))
    }

    @Test fun `oversize entry refused without fetching its bytes`() {
        val bytes = ZipFixtures.build(
            listOf(
                Triple("p01.jpg", ZipEntry.STORED, first),
                Triple("padding.bin", ZipEntry.STORED, ByteArray(1024)),
            ),
        )
        val central = findSig(bytes, 0x02014B50)
        assertTrue(central >= 0)
        patchU32(bytes, central + 24, 64L * 1024 * 1024)
        val fake = FakeFtpTransport(bytes)
        val source = FtpZipSource.open(fake, path)
        val before = fake.readCalls
        try {
            source.openPage(0).use { it.readBytes() }
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertNotNull(expected)
        } finally {
            source.close()
        }
        assertEquals(before, fake.readCalls)
    }

    @Test fun `empty archive opens with no pages and no cover`() {
        val source = FtpZipSource.open(FakeFtpTransport(ZipFixtures.build(emptyList())), path)
        assertTrue(source.pages.isEmpty())
        var thrown: IOException? = null
        try {
            source.openCover()
        } catch (expected: IOException) {
            thrown = expected
        } finally {
            source.close()
        }
        assertNotNull(thrown)
    }

    @Test fun `cover over the bound is refused`() {
        val big = ZipFixtures.pageBytes(3, 13_000_000)
        val bytes = ZipFixtures.build(listOf(Triple("p01.jpg", ZipEntry.STORED, big)))
        val source = FtpZipSource.open(FakeFtpTransport(bytes), path)
        var thrown: IOException? = null
        try {
            source.openCover().use { it.readBytes() }
        } catch (expected: IOException) {
            thrown = expected
        } finally {
            source.close()
        }
        val message = requireNotNull(requireNotNull(thrown).message)
        assertTrue(message.contains("cover"))
    }

    @Test fun `page index out of bounds`() {
        val source = FtpZipSource.open(FakeFtpTransport(archive()), path)
        var thrown: IndexOutOfBoundsException? = null
        try {
            source.openPage(5)
        } catch (expected: IndexOutOfBoundsException) {
            thrown = expected
        } finally {
            source.close()
        }
        assertNotNull(thrown)
    }
}
