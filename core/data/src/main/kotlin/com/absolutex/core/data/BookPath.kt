package com.absolutex.core.data

import java.io.File
import java.net.URLDecoder

/**
 * A book's [android path][LibraryBook.path] split into its parent folder and its own name — for
 * the library's display name and search (§5.1), and for anything else (a per-folder scope, a
 * raw-filename tiebreak) that needs a book's real folder or filename rather than its raw Uri.
 *
 * A SAF document Uri's document id is percent-encoded as ONE path segment: any "/" that is part
 * of the id's own relative path (e.g. "primary:Comics/Chapter 1/001.jpg") comes back as a literal
 * "%2F", not a Uri path separator. `File(uri).parent` therefore sees the same one segment for
 * every document under a tree and can never tell two folders apart. Decoding that segment and
 * splitting IT on its last "/" recovers the real parent and name. A filesystem path never had
 * this problem and is handled by [File] exactly as before.
 *
 * The decoded id also carries a `<root>:` prefix (e.g. "primary:Batman 001.cbz" for a document at
 * the tree's own root, with no "/" at all) — that root label is not part of the name and is
 * stripped before splitting, so a root-level document's name is never "primary:Batman 001.cbz".
 */
object BookPath {

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
        val relativePath = documentId.substringAfter(':', documentId)
        val slash = relativePath.lastIndexOf('/')
        return if (slash < 0) {
            prefix to relativePath
        } else {
            (prefix + relativePath.substring(0, slash)) to relativePath.substring(slash + 1)
        }
    }

    /** Uri.encode never leaves a raw "+" behind (it becomes "%2B"), so plain [URLDecoder] is safe here. */
    private fun decode(segment: String): String =
        runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
}
