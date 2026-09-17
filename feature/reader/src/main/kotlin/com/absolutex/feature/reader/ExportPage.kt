package com.absolutex.feature.reader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream

/** Export beyond this edge is a poster, not a page; PDF pages render up to it. */
internal const val EXPORT_MAX_EDGE = 3000

private const val EXPORT_QUALITY = 95
private val ILLEGAL_IN_NAME = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

/** The book's own extension, which an exported page should not inherit: "Batman.cbr p1.jpg". */
private val CONTAINER_EXTENSION = Regex("""\.[A-Za-z0-9]{2,4}$""")

/**
 * A file name for one exported page (§5.2). Anything a file system or MediaStore would reject is
 * replaced, so a book called "Batman: Year One / v2" still exports.
 */
internal fun exportFileName(bookTitle: String, pageIndex: Int, extension: String): String {
    // Underscores are trimmed with the whitespace: a title that was only separators would
    // otherwise export as "___ p1.jpg".
    val title = ILLEGAL_IN_NAME.replace(bookTitle, "_")
        .let { CONTAINER_EXTENSION.replace(it, "") }
        .trim { it.isWhitespace() || it == '_' }
        .ifEmpty { "page" }
    return "$title p${pageIndex + 1}.$extension"
}

/**
 * Writes one page to Pictures/Absolutex and returns its Uri, or null if the store refused it.
 *
 * The page is stored pending and published only once its bytes are written, so a gallery never
 * shows a half-written image, and a failure leaves nothing behind to clean up.
 */
internal fun Context.exportImage(name: String, mimeType: String, write: (java.io.OutputStream) -> Unit): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Absolutex")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = contentResolver.insert(collection, values) ?: return null
    runCatching { contentResolver.openOutputStream(uri)?.use(write) ?: error("no output stream") }
        // Any failure must take the pending row with it: a row left pending is invisible in the
        // gallery and nothing ever cleans it up.
        .onFailure { runCatching { contentResolver.delete(uri, null, null) } }
        .getOrThrow()
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    contentResolver.update(uri, values, null, null)
    return uri
}

/** Exports the page's own bytes, unchanged: an export of a scan should not re-encode it. */
internal fun Context.exportPageBytes(bookTitle: String, pageIndex: Int, entryName: String, bytes: ByteArray): Uri? {
    val extension = entryName.substringAfterLast('.', "jpg").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
    return exportImage(exportFileName(bookTitle, pageIndex, extension), mime) { it.write(bytes) }
}

/** Exports a rendered page (PDF), which has no encoded bytes of its own. */
internal fun Context.exportPageBitmap(bookTitle: String, pageIndex: Int, bitmap: Bitmap): Uri? {
    val encoded = ByteArrayOutputStream().also {
        bitmap.compress(Bitmap.CompressFormat.JPEG, EXPORT_QUALITY, it)
    }.toByteArray()
    return exportImage(exportFileName(bookTitle, pageIndex, "jpg"), "image/jpeg") { it.write(encoded) }
}
