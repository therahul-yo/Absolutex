package com.absolutex.feature.reader

import android.content.Context
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.absolutex.core.data.ContentResolverTree
import com.absolutex.core.scan.LibraryScanner
import com.absolutex.model.BookIdentity
import com.absolutex.source.ContainerFormat
import com.absolutex.source.EntryFilter
import com.absolutex.source.FormatSniffer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.absolutex.source.libarchive.LibArchiveSource
import com.absolutex.source.pdf.PdfDocument
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Opens the book behind [uri], choosing the format by its content rather than its name: a SAF
 * document's name is whatever the provider says, and plenty of PDFs arrive named .cbz.
 *
 * The decision is [FormatSniffer] in :source:api rather than an inline check, so the cases that
 * actually go wrong — a ZIP wearing .cbr, a CBZ that merely contains "%PDF-", a truncated header
 * — are JVM tests instead of something only a device can reproduce. It costs the same single
 * header read the inline check did.
 */
internal fun Context.openBook(uri: Uri): Closeable {
    val head = ParcelFileDescriptor.AutoCloseInputStream(openDescriptor(uri))
        .use { it.readNBytes(FormatSniffer.HEADER_BYTES) }
    if (FormatSniffer.detect(head) != ContainerFormat.PDF) {
        // Everything else is libarchive's, which reads more formats than the sniffer names — so
        // UNKNOWN is a route, not a failure, and behaviour for every non-PDF is unchanged.
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
        File(requireNotNull(uri.path) { "file uri has no path" }),
        ParcelFileDescriptor.MODE_READ_ONLY,
    )
    else -> contentResolver.openFileDescriptor(uri, "r")
        ?: error("could not open document")
}

/**
 * The book's identity, however it was reached — see BookIdentity. Keying progress by the Uri
 * string gave one comic a different identity per route, so the library could never match a
 * shelf entry to its reading position.
 *
 * A folder book's [Uri] is the folder itself, which has no length of its own: a directory
 * document's SIZE column is null, and `File(path).length()` of a directory is not the book's
 * size either. Both branches below fall back to the same sum the scanner used when it found it
 * (see [LibraryScanner]) — its image pages' sizes — so `contentKey` and this identity always agree.
 */
internal fun Context.identityOf(uri: Uri): String = when (uri.scheme) {
    "file", null -> uri.path?.let { File(it) }?.let { fileIdentity(it, uri) } ?: uri.toString()
    else -> documentIdentity(uri)
}

private fun fileIdentity(file: File, uri: Uri): String {
    val size = if (file.isDirectory) folderPageBytes(file) else file.length()
    return BookIdentity.ofOrFallback(file.name, size, uri.toString())
}

/** A folder book's size, exactly as [LibraryScanner] sums it: its immediate image pages, not sub-folders. */
private fun folderPageBytes(dir: File): Long =
    dir.listFiles()
        ?.filterNot { LibraryScanner.shouldSkip(it, includeHidden = false) }
        ?.filter { it.isFile && EntryFilter.isPage(it.name) }
        ?.sumOf { it.length() }
        ?: 0L

private fun Context.documentIdentity(uri: Uri): String = contentResolver.query(
    uri,
    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE),
    null,
    null,
    null,
)?.use { c ->
    if (!c.moveToFirst()) return@use null
    val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
    val mime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE).takeIf { it >= 0 }?.let(c::getString)
    val size = if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
        documentFolderPageBytes(uri)
    } else {
        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
    }
    BookIdentity.ofOrFallback(name, size, uri.toString())
} ?: uri.toString()

/** [folderPageBytes], for a SAF folder document — its children by the same [ContentResolverTree] a scan uses. */
private fun Context.documentFolderPageBytes(documentUri: Uri): Long =
    ContentResolverTree(this).children(documentUri.toString())
        .filterNot { LibraryScanner.shouldSkip(it.name, it.isDirectory, includeHidden = false) }
        .filter { !it.isDirectory && EntryFilter.isPage(it.name) }
        .sumOf { it.sizeBytes }
