package com.absolutex.source.libarchive

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * 7z (.cb7) through the real native library: needs a device (or emulator), which is why CI
 * cannot run it. `tools/test-archive-7z.sh` runs the same fixtures through the same JNI on a host
 * JVM; this is the run that proves liblzma is inside the arm64 `.so` and the bridge works on ART.
 *
 * The bundled fixtures (assets/7z-*.cb7.b64, made with 7-Zip 26.03, two 1x1 PNG pages plus a
 * ComicInfo.xml) always run. The corpus cases (`tools/make-corpus.py`, which needs a 7z binary)
 * are staged by hand like the rest of the corpus and skipped when absent.
 */
@RunWith(AndroidJUnit4::class)
class SevenZipSourceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun withFixture(name: String, block: (File) -> Unit) {
        val file = File.createTempFile("7z-", ".cb7", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("7z-$name.cb7.b64").use { input ->
                file.writeBytes(Base64.getMimeDecoder().decode(input.readBytes()))
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

    private fun assertTwoPngPages(file: File) {
        open(file).use { src ->
            assertFalse(src.isEncrypted)
            assertEquals(listOf("001.png", "002.png"), src.pages.map { it.entryName })
            assertEquals("7z fixture", src.comicInfo?.series)
            val pngMagic = byteArrayOf(-119, 80, 78, 71)
            src.pages.indices.forEach { i ->
                assertArrayEquals(pngMagic, src.openPage(i).use { it.readBytes() }.copyOf(4))
            }
            // A run of pages goes through nativeExtractWindow: one walk, and for a solid archive
            // one decode of the folder instead of one per page.
            val window = src.openPages(listOf(0, 1)).map { it!!.use { s -> s.readBytes().size } }
            assertEquals(src.pages.indices.map { src.openPage(it).use { s -> s.readBytes().size } }, window)
        }
    }

    @Test fun lzma2_non_solid_cb7_opens_and_yields_pages() = withFixture("lzma2-nonsolid") { assertTwoPngPages(it) }

    @Test fun lzma2_solid_cb7_opens_and_yields_pages() = withFixture("lzma2-solid") { assertTwoPngPages(it) }

    @Test fun content_encrypted_7z_reports_unsupported_encryption_without_asking_for_a_password() =
        withFixture("encrypted-content") { file ->
            assertThrows(UnsupportedEncryptionException::class.java) { open(file).close() }
            val password = "corpus-only".toCharArray()
            assertThrows(UnsupportedEncryptionException::class.java) { open(file, password).close() }
        }

    @Test fun header_encrypted_7z_reports_unsupported_encryption() = withFixture("encrypted-header") { file ->
        assertThrows(UnsupportedEncryptionException::class.java) { open(file).close() }
    }

    // Corpus cases from tools/make-corpus.py: 10 PNG pages, solid and non-solid LZMA2.
    private fun openCorpus(name: String, block: (LibArchiveSource) -> Unit) {
        val dir = InstrumentationRegistry.getArguments().getString("corpusDir")?.let { File(it) }
            ?: instrumentation.targetContext.getExternalFilesDir(null)!!
        val file = File(dir, name)
        assumeTrue("corpus file missing: $file", file.exists())
        open(file).use(block)
    }

    @Test fun corpus_solid_cb7_yields_every_page_in_order() = openCorpus("14_solid.cb7") { src ->
        assertEquals(10, src.pages.size)
        val last = src.pages.size - 1
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71), src.openPage(last).use { it.readBytes() }.copyOf(4))
    }

    @Test fun corpus_lzma2_non_solid_cb7_yields_every_page_in_order() =
        openCorpus("28_lzma2_nonsolid.cb7") { src ->
            assertEquals(10, src.pages.size)
            assertEquals("Absolutex Corpus", src.comicInfo?.series)
        }
}
