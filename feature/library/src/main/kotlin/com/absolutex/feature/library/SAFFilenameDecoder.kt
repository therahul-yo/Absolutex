package com.absolutex.feature.library

import android.net.Uri
import java.net.URLDecoder

/**
 * Returns the real filename segment for both file-system paths and SAF content:// Uris.
 *
 * For SAF books, [path] is a `content://` document Uri whose last path segment is a
 * percent-encoded document id (`primary%3AComics%2FBatman%20001.cbz`). The real filename
 * is the last `/`-delimited part after URL-decoding that segment.
 */
internal fun decodedFilename(path: String): String {
    if (!path.startsWith("content://")) {
        return java.io.File(path).name
    }
    val raw = path.substringAfterLast("/").ifEmpty { path }
    val decoded = URLDecoder.decode(raw, "UTF-8")
    val split = decoded.split("/", ":")
    return split.last().ifEmpty { decoded }.ifEmpty { java.io.File(path).name }
}
