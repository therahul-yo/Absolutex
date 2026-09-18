package com.absolutex.model

/**
 * The one identity a book has, however it was reached.
 *
 * The same comic arrives by different routes: a `file://` path when tapped on a library shelf, a
 * `content://` Uri when picked through SAF, and a filesystem path when the scanner finds it. Keying
 * reading progress by the Uri string gave one book up to three identities, so the library's
 * Reading shelf could never match progress to the book it belonged to.
 *
 * Filename and size are what every route can observe without opening the archive, and they are
 * already the library's deduplication key (§5.1) — so progress, library and dedup now agree.
 * Two different comics sharing a name and a byte-exact size is not a collision worth hashing
 * content to avoid.
 */
object BookIdentity {

    fun of(displayName: String, sizeBytes: Long): String = "$displayName:$sizeBytes"

    /**
     * The reverse of [of]: the display name folded into [contentKey], given the same [sizeBytes]
     * it was built with.
     *
     * Recovering it here rather than persisting a second column is sound because [of]'s shape
     * never changes — every `contentKey` the library persists came from exactly this join — so
     * subtracting the known, exact suffix cannot lose or misread anything.
     */
    fun nameOf(contentKey: String, sizeBytes: Long): String = contentKey.removeSuffix(":$sizeBytes")

    /**
     * Identity when the route may not report both parts, falling back to [fallback].
     *
     * A SAF provider is allowed to omit the size, and a nameless or sizeless identity would
     * collide across unrelated books. Falling back to the Uri keeps such a book's progress
     * stable on its own route, which is the most that can honestly be offered.
     */
    fun ofOrFallback(displayName: String?, sizeBytes: Long?, fallback: String): String =
        if (displayName.isNullOrBlank() || sizeBytes == null || sizeBytes < 0) fallback
        else of(displayName, sizeBytes)
}
