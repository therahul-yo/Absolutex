package com.absolutex.source.libarchive

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bundled benign fixture: always runs, unlike optional device-staged corpus cases. */
@RunWith(AndroidJUnit4::class)
class ArchivePasswordTest {
    private fun withFixture(block: (File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("encrypted-", ".cbz", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("encrypted-zipcrypto.cbz.b64").use { input ->
                file.writeBytes(java.util.Base64.getMimeDecoder().decode(input.readBytes()))
            }
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun open(file: File, password: CharArray? = null): LibArchiveSource =
        LibArchiveSource.open(password) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

    @Test fun encrypted_archive_requires_a_password_at_open() = withFixture { file ->
        assertThrows(PasswordRequiredException::class.java) { open(file).close() }
    }

    @Test fun rejected_password_is_not_partial_recovery_or_missing_metadata() = withFixture { file ->
        val password = "not-the-password".toCharArray()
        try {
            val error = assertThrows(WrongPasswordException::class.java) { open(file, password).close() }
            assertEquals("Archive password rejected", error.message)
        } finally {
            password.fill('\u0000')
        }
    }

    @Test fun correct_password_reads_pages_and_metadata_and_is_not_global() = withFixture { file ->
        val password = "corpus-only".toCharArray()
        val source = try {
            open(file, password)
        } finally {
            password.fill('\u0000')
        }
        source.use {
            assertTrue(it.isEncrypted)
            assertEquals(1, it.pages.size)
            assertEquals("Encrypted fixture", it.comicInfo?.series)
            assertEquals(1, it.comicInfo?.pageCount)
            assertEquals(null, it.pageReadability)
            val bytes = it.openPage(0).use { input -> input.readBytes() }
            assertEquals(68, bytes.size)
            assertArrayEquals(byteArrayOf(-119, 80, 78, 71), bytes.copyOf(4))
        }
        assertThrows(IOException::class.java) { source.openPage(0).close() }
        assertThrows(PasswordRequiredException::class.java) { open(file).close() }
    }

    @Test fun ordinary_archive_still_opens_with_a_trailing_lambda() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("ordinary-", ".cbz", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("001.png"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            LibArchiveSource.open {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.use {
                assertFalse(it.isEncrypted)
                assertEquals(null, it.pageReadability)
                assertArrayEquals(byteArrayOf(1, 2, 3), it.openPage(0).use { input -> input.readBytes() })
            }
        } finally {
            file.delete()
        }
    }

    @Test fun torn_first_page_with_correct_password_degrades_instead_of_refusing_the_book() = withFixture { file ->
        corruptFirstPagePayload(file)
        val password = "corpus-only".toCharArray()
        // Before the probe fix this threw IOException("Archive password rejected or entry
        // unreadable"): null from nativeExtract means the entry is unreadable, NOT that the
        // password was rejected — typed exceptions carry those. Here it must simply open.
        val source = try {
            open(file, password)
        } finally {
            password.fill('\u0000')
        }
        source.use {
            assertTrue(it.isEncrypted)
            assertEquals(1, it.pages.size)
            // Not a recovery open (the listing is complete and ComicInfo agrees on one page), so
            // no count is taken and nothing is reported — exactly as a torn page in a plain archive.
            // The page fails when it is reached, below, which is the degrade this test pins.
            assertEquals(null, it.pageReadability)
            assertEquals("Encrypted fixture", it.comicInfo?.series)
            assertThrows(IOException::class.java) { it.openPage(0).close() }
        }
    }

    /**
     * Corrupts the payload of the fixture's first image entry, in place, leaving the framing
     * intact: the local header must still parse (listing stays complete), and the 12-byte
     * ZipCrypto header is skipped because its check byte would turn the corruption into a
     * wrong-password signal instead of a CRC failure.
     */
    private fun corruptFirstPagePayload(file: File) {
        val bytes = file.readBytes()
        var offset = 0
        while (offset + ZIP_LOCAL_HEADER_MIN <= bytes.size) {
            val header = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.LITTLE_ENDIAN)
            if (header.int != ZIP_LOCAL_SIGNATURE) break // central directory reached
            val flags = header.getShort(FLAGS_OFFSET).toInt()
            val csize = header.getInt(COMPRESSED_SIZE_OFFSET)
            val nameLen = header.getShort(NAME_LEN_OFFSET).toInt() and 0xFFFF
            val extraLen = header.getShort(EXTRA_LEN_OFFSET).toInt() and 0xFFFF
            val name = String(bytes, offset + ZIP_LOCAL_HEADER_MIN, nameLen, Charsets.UTF_8)
            val dataStart = offset + ZIP_LOCAL_HEADER_MIN + nameLen + extraLen
            if (name.endsWith(".png")) {
                check(flags and FLAG_ENCRYPTED != 0) { "fixture entry should be encrypted" }
                val payloadStart = dataStart + ZIPCRYPTO_HEADER_BYTES
                check(dataStart + csize - payloadStart >= CORRUPT_BYTES) { "payload too small" }
                for (i in payloadStart until payloadStart + CORRUPT_BYTES) {
                    bytes[i] = (bytes[i].toInt() xor 0xFF).toByte()
                }
                file.writeBytes(bytes)
                return
            }
            offset = dataStart + csize
            if (flags and FLAG_DATA_DESCRIPTOR != 0) {
                val sig = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
                offset += if (sig == ZIP_DATADESC_SIGNATURE) DATADESC_WITH_SIG_BYTES else DATADESC_NO_SIG_BYTES
            }
        }
        error("fixture has no page entry to corrupt")
    }

    private companion object {
        const val ZIP_LOCAL_SIGNATURE = 0x04034B50
        const val ZIP_DATADESC_SIGNATURE = 0x08074B50
        const val ZIP_LOCAL_HEADER_MIN = 30
        const val FLAGS_OFFSET = 6
        const val COMPRESSED_SIZE_OFFSET = 18
        const val NAME_LEN_OFFSET = 26
        const val EXTRA_LEN_OFFSET = 28
        const val FLAG_ENCRYPTED = 0x1
        const val FLAG_DATA_DESCRIPTOR = 0x8
        const val ZIPCRYPTO_HEADER_BYTES = 12
        const val CORRUPT_BYTES = 32
        const val DATADESC_WITH_SIG_BYTES = 16
        const val DATADESC_NO_SIG_BYTES = 12
    }
}
