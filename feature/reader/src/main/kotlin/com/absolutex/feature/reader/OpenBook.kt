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
import com.absolutex.source.folder.FolderComicSource
import com.absolutex.source.folder.FolderEntry
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
    // Folders are asked about first: a folder is not a container with an unfamiliar header, it
    // has no header at all and no descriptor worth opening. See [folderPages] for the cost.
    val folder = folderPages(uri)
    if (folder != null) return FolderComicSource.open(folder)
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
    val size = if (file.isDirectory) filePages(file).sumOf { it.sizeBytes } else file.length()
    return BookIdentity.ofOrFallback(file.name, size, uri.toString())
}

/**
 * A folder book's pages, or null when [uri] is not a folder at all.
 *
 * One definition, two readers: the book's identity is the sum of these entries' sizes and its
 * page list is these same entries in natural order (see [FolderComicSource]). Selecting them
 * twice, in two places, is how a folder book's saved position stops matching its shelf entry.
 *
 * Cost, since this now runs before every open: on a file path it is one `isDirectory` stat. On
 * SAF it is one `getType` call, and the child walk below runs only once that answers yes — so an
 * ordinary container pays a single extra provider round trip, not a listing. A provider that
 * answers null degrades to exactly the previous behaviour: not a folder, open it as a container.
 *
 * Any directory opens, including one [LibraryScanner] would not have listed as a book because it
 * holds sub-folders. That difference is deliberate: the scanner decides what belongs on a shelf,
 * this decides how to read what the user asked for, and opening its immediate pages beats the
 * failure a directory used to produce here.
 */
private fun Context.folderPages(uri: Uri): List<FolderEntry>? = when (uri.scheme) {
    "file", null -> uri.path?.let(::File)?.takeIf { it.isDirectory }?.let(::filePages)
    else -> if (contentResolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR) {
        documentFolderPages(uri)
    } else {
        null
    }
}

/** Exactly what [LibraryScanner] selected when it found the folder: its immediate image children. */
private fun filePages(dir: File): List<FolderEntry> =
    dir.listFiles()
        ?.filterNot { LibraryScanner.shouldSkip(it, includeHidden = false) }
        ?.filter { it.isFile && EntryFilter.isPage(it.name) }
        ?.map { page -> FolderEntry(page.name, page.length()) { page.inputStream() } }
        .orEmpty()

/**
 * The name a reader recognises. A SAF document's last path segment is its document id — on this
 * provider "msf:1000092392" — so the title bar showed that; the provider's DISPLAY_NAME is the
 * filename. A file path or a remote Uri already ends in its name. Degrades to the old behaviour.
 */
internal fun Context.displayNameOf(uri: Uri): String {
    val fromProvider = if (uri.scheme == "content") {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    } else {
        null
    }
    return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
}

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
        documentFolderPages(uri).sumOf { it.sizeBytes }
    } else {
        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
    }
    BookIdentity.ofOrFallback(name, size, uri.toString())
} ?: uri.toString()

/**
 * [filePages], for a SAF folder document — its children by the same [ContentResolverTree] a scan uses.
 *
 * Each entry's opener captures this [Context] for as long as the book stays open, so it must be
 * the application context — which is what `ContextBookOpener` is constructed with.
 */
private fun Context.documentFolderPages(documentUri: Uri): List<FolderEntry> =
    ContentResolverTree(this).children(documentUri.toString())
        .filterNot { LibraryScanner.shouldSkip(it.name, it.isDirectory, includeHidden = false) }
        .filter { !it.isDirectory && EntryFilter.isPage(it.name) }
        .map { page ->
            FolderEntry(page.name, page.sizeBytes) {
                // Generic message: the raw Uri must not reach the UI (see openDescriptor).
                contentResolver.openInputStream(Uri.parse(page.uri)) ?: throw IOException("page is unreadable")
            }
        }
