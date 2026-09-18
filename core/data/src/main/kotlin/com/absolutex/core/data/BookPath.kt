package com.absolutex.core.data

import java.io.File
import java.net.URLDecoder

/**
 * A book's [android path][LibraryBook.path] split into its parent folder and its own name — for
 * [NextBookScope.CURRENT_FOLDER] and the raw-filename tiebreak (§5.2).
 *
 * A SAF document Uri's document id is percent-encoded as ONE path segment: any "/" that is part
 * of the id's own relative path (e.g. "primary:Comics/Chapter 1/001.jpg") comes back as a literal
 * "%2F", not a Uri path separator. `File(uri).parent` therefore sees the same one segment for
 * every document under a tree and can never tell two folders apart. Decoding that segment and
 * splitting IT on its last "/" recovers the real parent and name. A filesystem path never had
 * this problem and is handled by [File] exactly as before.
 */
internal object BookPath {

    private const val DOCUMENT_SEGMENT = "/document/"

    fun parentOf(path: String): String = split(path).first

    fun nameOf(path: String): String = split(path).second

    private fun split(path: String): Pair<String, String> {
        val documentAt = path.lastIndexOf(DOCUMENT_SEGMENT)
        if (!path.startsWith("content://") || documentAt < 0) {
            val file = File(path)
            return (file.parent ?: "") to file.name
        }
        val prefix = path.substring(0, documentAt + DOCUMENT_SEGMENT.length)
        val documentId = decode(path.substring(documentAt + DOCUMENT_SEGMENT.length))
        val slash = documentId.lastIndexOf('/')
        return if (slash < 0) {
            prefix to documentId
        } else {
            (prefix + documentId.substring(0, slash)) to documentId.substring(slash + 1)
        }
    }

    /** Uri.encode never leaves a raw "+" behind (it becomes "%2B"), so plain [URLDecoder] is safe here. */
    private fun decode(segment: String): String =
        runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
}
