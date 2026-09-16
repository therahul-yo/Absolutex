package com.absolutex.feature.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.absolutex.source.libarchive.LibArchiveSource
import com.absolutex.source.pdf.PdfDocument
import java.io.Closeable
import java.io.IOException

/** PDF allows junk before the header, up to 1024 bytes of it. */
private const val PDF_SNIFF_BYTES = 1024
private const val PDF_MAGIC = "%PDF-"

/**
 * Opens the book behind [uri], choosing the format by its content rather than its name: a SAF
 * document's name is whatever the provider says, and plenty of PDFs arrive named .cbz.
 */
internal fun Context.openBook(uri: Uri): Closeable {
    val head = ParcelFileDescriptor.AutoCloseInputStream(openDescriptor(uri))
        .use { it.readNBytes(PDF_SNIFF_BYTES) }
    if (!String(head, Charsets.ISO_8859_1).contains(PDF_MAGIC)) {
        // A fresh descriptor per read — a shared SAF fd corrupts parallel reads.
        return LibArchiveSource.open { openDescriptor(uri) }
    }
    // One descriptor for the document's life: every PDFium read is a pread (see PdfDocument).
    val pfd = openDescriptor(uri)
    return try {
        PdfDocument.open(pfd)
    } catch (e: IOException) {
        pfd.close()
        throw e
    }
}

/**
 * Opens a descriptor for either a SAF document or a plain file path.
 *
 * §5.1 needs device-storage locations, which are real paths, not content Uris — and a
 * file path also avoids SAF entirely where the app already has access, which is both
 * faster and what makes the reader drivable from an instrumented benchmark.
 */
internal fun Context.openDescriptor(uri: Uri): ParcelFileDescriptor = when (uri.scheme) {
    // Messages below stay generic: the raw Uri must not reach the UI (nor be
    // formatted into exceptions that the UI renders) — logcat gets the detail.
    "file", null -> ParcelFileDescriptor.open(
        java.io.File(requireNotNull(uri.path) { "file uri has no path" }),
        ParcelFileDescriptor.MODE_READ_ONLY,
    )
    else -> contentResolver.openFileDescriptor(uri, "r")
        ?: error("could not open document")
}
