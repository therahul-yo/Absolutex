package com.absolutex.source.pdf

import android.graphics.Bitmap

/**
 * Thin JNI surface. Stateful, unlike the libarchive bridge — see pdfium_jni.c for why a
 * document is held open rather than re-opened per call.
 *
 * Nothing here validates its arguments; [PdfDocument] owns that, and owns the handle
 * lifetime. Calling any of these with a stale handle is a use-after-free.
 */
internal object Pdfium {
    init {
        // libabsolutex_pdf.so carries a DT_NEEDED on libpdfium.so, so the linker would pull
        // it in anyway. Loading it first turns a packaging mistake into a clear
        // "couldn't find libpdfium.so" instead of an opaque failure to load our own wrapper.
        System.loadLibrary("pdfium")
        System.loadLibrary("absolutex_pdf")
    }

    /**
     * Opens a document. Returns a handle, or **0** on failure with the FPDF error code written
     * to `errorOut[0]`.
     *
     * A handle is deliberately not testable for sign: bionic tags heap pointers on arm64, so a
     * valid handle is a negative Long. See set_open_error() in pdfium_jni.c.
     *
     * [password] is real UTF-8 bytes, not a String: a jstring would cross as MODIFIED
     * UTF-8 and mangle any non-BMP character. See nativeOpen in pdfium_jni.c.
     */
    @JvmStatic external fun nativeOpen(fd: Int, password: ByteArray?, errorOut: IntArray): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativePageCount(handle: Long): Int

    /** Writes width and height in points into [out]; returns false if the page is unreadable. */
    @JvmStatic external fun nativePageSize(handle: Long, index: Int, out: FloatArray): Boolean

    @JvmStatic external fun nativeRenderTile(
        handle: Long,
        pageIndex: Int,
        tileLeft: Int,
        tileTop: Int,
        scaledWidth: Int,
        scaledHeight: Int,
        flags: Int,
        bitmap: Bitmap,
    ): Boolean

    /** `{String[] titles, int[] depths, int[] pageIndices}`, equal length, pre-order. */
    @JvmStatic external fun nativeOutline(handle: Long): Array<Any>?
}
