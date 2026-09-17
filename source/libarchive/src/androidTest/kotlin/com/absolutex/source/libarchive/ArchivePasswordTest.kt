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
}
