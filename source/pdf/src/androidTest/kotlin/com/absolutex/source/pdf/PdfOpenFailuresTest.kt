package com.absolutex.source.pdf

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The open-failure corpus: what the JNI layer does with hostile-but-plausible input. Fixtures
 * are built in memory by [ProblemPdfs] — no binaries checked in — and every case asserts the
 * open contract from `PdfDocument.open`'s KDoc: encrypted throws `PdfPasswordException` without
 * its password, damaged-but-repairable opens, truncated throws `PdfException` rather than
 * crashing the process.
 */
@RunWith(AndroidJUnit4::class)
class PdfOpenFailuresTest {

    private lateinit var dir: File
    private val files = mutableListOf<File>()

    @Before fun setUp() {
        // Same dir choice as PdfDocumentTest: scoped storage denies the shared Download dir.
        dir = requireNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        )
    }

    @After fun tearDown() {
        files.forEach { it.delete() }
    }

    private fun write(name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }.also { files += it }

    private fun open(file: File, password: String? = null): PdfDocument =
        PdfDocument.open(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), password)

    @Test fun encryptedOpensWithItsPassword() {
        open(write("encrypted.pdf", ProblemPdfs.encrypted()), ProblemPdfs.USER_PASSWORD).use {
            assertEquals(1, it.pageCount)
        }
    }

    @Test fun encryptedWithoutPasswordThrowsPasswordException() {
        val file = write("encrypted-no-password.pdf", ProblemPdfs.encrypted())
        assertThrows(PdfPasswordException::class.java) { open(file) }
    }

    @Test fun encryptedWithWrongPasswordThrowsPasswordException() {
        val file = write("encrypted-wrong-password.pdf", ProblemPdfs.encrypted())
        assertThrows(PdfPasswordException::class.java) { open(file, "wrong") }
    }

    @Test fun linearizedOpens() {
        open(write("linearized.pdf", ProblemPdfs.linearized())).use {
            assertEquals(1, it.pageCount)
        }
    }

    @Test fun brokenXrefOffsetsAreRepaired() {
        open(write("broken-xref.pdf", ProblemPdfs.brokenXref())).use {
            assertEquals(1, it.pageCount)
        }
    }

    @Test fun leadingJunkBeforeTheHeaderOpens() {
        open(write("leading-junk.pdf", ProblemPdfs.leadingJunk())).use {
            assertEquals(1, it.pageCount)
        }
    }

    @Test fun truncatedThrowsPdfExceptionInsteadOfCrashing() {
        val file = write("truncated.pdf", ProblemPdfs.truncated())
        val thrown = assertThrows(PdfException::class.java) { open(file) }
        assertNotNull(thrown.message)
    }
}
