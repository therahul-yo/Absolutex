package com.absolutex.source.libarchive

import java.security.MessageDigest

/**
 * Where the solid-archive cache draws its lines. Pure arithmetic, so each line is a unit test.
 */
internal object SolidCachePolicy {
    /** Ceiling for everything the cache holds, across all archives. */
    const val MAX_CAP_BYTES = 1L shl 30

    /**
     * A solid block smaller than this decodes in well under a second even on a phone, so paging
     * through it is already smooth and a copy on disk would buy nothing.
     */
    const val MIN_BOOK_BYTES = 32L shl 20

    /** The cache never takes more than this share of the space it could be given. */
    private const val FREE_SPACE_DIVISOR = 4

    /**
     * The total the cache may hold: a quarter of the free space plus what the cache already holds
     * (that space is the cache's to reuse), and never more than [MAX_CAP_BYTES].
     */
    fun capBytes(freeBytes: Long, cacheBytes: Long): Long =
        ((freeBytes.coerceAtLeast(0) + cacheBytes.coerceAtLeast(0)) / FREE_SPACE_DIVISOR)
            .coerceAtMost(MAX_CAP_BYTES)

    /** Whether a solid book of [bookBytes] is worth caching under [capBytes]. */
    fun worthCaching(bookBytes: Long, capBytes: Long): Boolean = bookBytes in MIN_BOOK_BYTES..capBytes
}

/**
 * The on-disk name of one archive's cache: a hash of what identifies the file, never a name.
 *
 * The identity (a Uri or path) is hashed and forgotten, so nothing the user named or where they
 * keep it appears in a directory listing or a log. Size and modification time are part of the key
 * so a replaced file gets a fresh cache instead of the old one's pages.
 */
object SolidCacheKey {
    private const val KEY_HEX_CHARS = 32

    fun of(identity: String, sizeBytes: Long, lastModifiedMillis: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$identity|$sizeBytes|$lastModifiedMillis".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(KEY_HEX_CHARS)
    }
}
