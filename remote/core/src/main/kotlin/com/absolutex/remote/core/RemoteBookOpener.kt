package com.absolutex.remote.core

import com.absolutex.source.ComicSource

/**
 * Opens a remote book behind an `absolutex-remote://<serverId>/<path>` Uri.
 *
 * Implemented by the backend lane once transports merge: resolving a server id takes the
 * server list, its secrets and a backend transport (SMB/FTP), none of which live in this
 * transport-agnostic module. [TransportBookOpener] below is the backend-agnostic shape —
 * backends supply the `(serverId, path) -> RangeTransport` function and Hilt binds it where
 * Hilt exists (this module is plain JVM). Transport failures (unreachable, auth) throw
 * IOException for the reader's generic error path; only the container format itself returns
 * [RemoteOpenResult.DownloadRequired].
 *
 * TODO(lead): route absolutex-remote:// Uris to the injected RemoteBookOpener in
 * ReaderViewModel.open (feature/reader ReaderViewModel.kt, the context.openBook(uri) call) —
 * OpenBook stays local-only; the opener returns a ComicSource the reader already knows.
 */
interface RemoteBookOpener {
    /**
     * Opens the book behind [uri]. Suspending: resolving the server id reads the server
     * list (DataStore), so callers must not call this on the main thread — the reader
     * calls it from its open coroutine, same as every other open path.
     */
    @Throws(java.io.IOException::class)
    suspend fun open(uri: String): RemoteOpenResult
}

/** Where an `absolutex-remote://` Uri points: which server, which path, which file name. */
data class RemoteLocation(val serverId: String, val path: String, val displayName: String)

/** Either a streaming source, or a clear download-required verdict for formats that cannot. */
sealed interface RemoteOpenResult {
    /** ZIP/CBZ streaming source plus the identity shared with a local copy of the same file. */
    data class Ready(
        val source: ComicSource,
        val identity: String,
        val displayName: String,
        val sizeBytes: Long,
    ) : RemoteOpenResult

    /** CBR/CB7/CBT (and non-archives): random access needs a full-file index — Phase F. */
    data class DownloadRequired(val reason: String) : RemoteOpenResult
}

/** Scheme for remote book Uris. */
const val REMOTE_URI_SCHEME = "absolutex-remote"

/**
 * Backend-agnostic opener: parses the Uri, asks the backend for the file's transport, and
 * opens it as a streaming source. One transport per open call — transports are cheap
 * (SMB/FTP connect lazily) and never shared across books.
 */
class TransportBookOpener(
    private val transports: suspend (serverId: String, path: String) -> RangeTransport,
) : RemoteBookOpener {

    override suspend fun open(uri: String): RemoteOpenResult {
        val location = parseRemoteUri(uri)
        val transport = transports(location.serverId, location.path)
        return CoreComicSource.open(transport, location.displayName)
    }
}

/** Parses `absolutex-remote://<serverId>/<path>`; anything else is a caller bug, not IO. */
fun parseRemoteUri(uri: String): RemoteLocation {
    val parsed = parseUri(uri)
    if (parsed.scheme != REMOTE_URI_SCHEME) {
        throw IllegalArgumentException("remote uri must use $REMOTE_URI_SCHEME: $uri")
    }
    val serverId = parsed.authority
    // The raw path, decoded exactly once: URI.getPath() already decodes %XX escapes, so
    // decoding that again would mangle literal escapes — and URLDecoder implements form
    // encoding, turning a legitimate '+' in a file name into a space. Decoding rawPath
    // once matches BookPath's single decode of the raw SAF document id, so encoded names
    // resolve to the literal names (and identities) of their local copies.
    val path = decode(parsed.rawPath.orEmpty())
    val displayName = path.substringAfterLast('/')
    if (serverId.isNullOrBlank() || displayName.isEmpty()) {
        throw IllegalArgumentException("remote uri needs a server and a file: $uri")
    }
    return RemoteLocation(serverId, path, displayName)
}

/**
 * Builds an `absolutex-remote://<serverId>/<path>` Uri from a server id and an
 * absolute file path — the inverse of [parseRemoteUri], and the only way callers
 * (browse UI, recents, widgets) may mint these Uris. Each `/`-separated segment is
 * encoded on its own so separators survive: spaces become `%20` (never `+`), a
 * literal `+` becomes `%2B`, `%` becomes `%25`, non-ASCII becomes UTF-8 escapes.
 * A file genuinely named `Batman + Robin.cbz` must arrive encoded — a raw `+`
 * decodes to a space by form rules, which is right for the parser and wrong for
 * a filename. Encode-decode round-trips exactly; see the encoder tests.
 */
fun encodeRemoteUri(serverId: String, path: String): String {
    require(serverId.isNotBlank() && '/' !in serverId) {
        "remote uri needs a plain server id"
    }
    require(path.startsWith("/")) { "remote path must be absolute: $path" }
    val encoded = path.split('/').joinToString("/") { encodeSegment(it) }
    return "$REMOTE_URI_SCHEME://$serverId$encoded"
}

private fun encodeSegment(segment: String): String =
    java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

private fun decode(segment: String): String =
    runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)

private fun parseUri(uri: String): java.net.URI {
    try {
        return java.net.URI(uri)
    } catch (e: java.net.URISyntaxException) {
        throw IllegalArgumentException("bad remote uri: $uri", e)
    }
}
