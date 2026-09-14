package com.absolutex.source.pdf

import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfDocumentTest {

    private lateinit var pdf: File

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The app's own external files dir, not /sdcard/Download: scoped storage denies an
        // instrumented test access to the shared Download directory with EACCES.
        val dir = requireNotNull(context.getExternalFilesDir(null))
        pdf = File(dir, "minimal.pdf").apply { writeBytes(MinimalPdf.singleRedRectPage()) }
    }

    @After fun tearDown() {
        pdf.delete()
    }

    private fun open(file: File = pdf): PdfDocument =
        PdfDocument.open(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

    @Test fun readsPageCount() {
        open().use { assertEquals(1, it.pageCount) }
    }

    @Test fun readsPageSizeInPoints() {
        open().use {
            val size = it.pageSize(0)
            assertEquals(MinimalPdf.WIDTH_PT, size.width, 0.5f)
            assertEquals(MinimalPdf.HEIGHT_PT, size.height, 0.5f)
        }
    }

    /*
     * The colour assertion is the point of this test. PDFium writes BGRA and Android's
     * ARGB_8888 is RGBA in memory; the render path relies on FPDF_REVERSE_BYTE_ORDER to
     * reconcile them. If that flag is ever dropped, red reads back as blue and this fails.
     */
    @Test fun rendersTileWithCorrectChannelOrder() {
        open().use { doc ->
            // PDF space has its origin bottom-left, device space top-left, so the rectangle
            // drawn at PDF y=100..400 lands at device y=392..692 on a 792pt-tall page.
            val tile = Rect(150, 450, 250, 550)
            val bitmap = doc.renderTile(0, tile, scale = 1f)

            assertEquals(100, bitmap.width)
            assertEquals(100, bitmap.height)
            // Device (200, 500) is well inside the rectangle: tile-local (50, 50).
            assertEquals(Color.RED, bitmap.getPixel(50, 50))
        }
    }

    @Test fun rendersPageBackgroundOpaqueWhite() {
        open().use { doc ->
            val bitmap = doc.renderTile(0, Rect(0, 0, 64, 64), scale = 1f)
            assertEquals(Color.WHITE, bitmap.getPixel(32, 32))
            assertEquals(255, Color.alpha(bitmap.getPixel(32, 32)))
        }
    }

    /* A wrong sign on the tile origin would still produce a plausible-looking bitmap, so
     * assert that two different tiles of the same page actually differ. */
    @Test fun tileOriginSelectsDifferentRegions() {
        open().use { doc ->
            val onRect = doc.renderTile(0, Rect(150, 450, 250, 550), scale = 1f)
            val offRect = doc.renderTile(0, Rect(0, 0, 100, 100), scale = 1f)
            assertEquals(Color.RED, onRect.getPixel(50, 50))
            assertEquals(Color.WHITE, offRect.getPixel(50, 50))
        }
    }

    @Test fun scaleMovesTheSameFeatureProportionally() {
        open().use { doc ->
            // At 2x every device coordinate doubles, so the rectangle's interior point
            // (200, 500) becomes (400, 1000).
            val bitmap = doc.renderTile(0, Rect(350, 950, 450, 1050), scale = 2f)
            assertEquals(Color.RED, bitmap.getPixel(50, 50))
        }
    }

    @Test fun reusesACallerSuppliedBitmap() {
        open().use { doc ->
            val tile = Rect(150, 450, 250, 550)
            val bitmap = android.graphics.Bitmap.createBitmap(
                tile.width(), tile.height(), android.graphics.Bitmap.Config.ARGB_8888,
            )
            doc.renderTileInto(0, tile, 1f, bitmap)
            assertEquals(Color.RED, bitmap.getPixel(50, 50))
        }
    }

    @Test fun documentWithoutOutlineReturnsEmptyList() {
        open().use { assertTrue(it.outline().isEmpty()) }
    }

    @Test fun rejectsAPageOutOfRange() {
        open().use { doc ->
            assertThrows(IndexOutOfBoundsException::class.java) { doc.pageSize(1) }
            assertThrows(IndexOutOfBoundsException::class.java) {
                doc.renderTile(1, Rect(0, 0, 16, 16), 1f)
            }
        }
    }

    @Test fun rejectsANonPdf() {
        val junk = File(pdf.parentFile, "junk.pdf").apply { writeBytes(ByteArray(2048) { 0x41 }) }
        try {
            val thrown = assertThrows(PdfException::class.java) { open(junk) }
            assertNotNull(thrown.message)
        } finally {
            junk.delete()
        }
    }

    @Test fun rejectsUseAfterClose() {
        val doc = open()
        doc.close()
        assertThrows(IllegalStateException::class.java) { doc.pageSize(0) }
    }

    @Test fun closeIsIdempotent() {
        val doc = open()
        doc.close()
        doc.close()
    }

    /*
     * The libarchive layer had a concurrency bug that only appeared under fan-out, so the
     * same shape of test guards this one. It asserts correctness under contention, not
     * speed — PDFium calls are serialised process-wide on purpose (see pdfium_jni.c).
     */
    @Test fun rendersCorrectlyUnderConcurrentCallers() {
        open().use { doc ->
            val pool = Executors.newFixedThreadPool(8)
            try {
                val work = List(64) {
                    Callable {
                        doc.renderTile(0, Rect(150, 450, 250, 550), scale = 1f)
                            .getPixel(50, 50)
                    }
                }
                val results = pool.invokeAll(work, 60, TimeUnit.SECONDS)
                results.forEach { assertEquals(Color.RED, it.get()) }
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
