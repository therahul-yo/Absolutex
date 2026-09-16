package com.absolutex.core.thumbnails

import java.security.MessageDigest

/**
 * Key derivation for thumbnail cache entries.
 *
 * Each request maps to one file named `sha256(sourceId|pageIndex|bucket)` in hex. Hashing (rather
 * than embedding the source id) keeps filenames a fixed length and free of separators, and the hex
 * alphabet cannot smuggle in a path traversal.
 */
object ThumbKeys {
    const val FILE_SUFFIX = ".thumb"

    private const val ALGORITHM = "SHA-256"
    private const val SEPARATOR = "|"
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val HEX_WIDTH = 2
    private const val BYTE_MASK = 0xFF
    private const val HIGH_SHIFT = 4
    private const val LOW_MASK = 0xF

    fun fileName(request: ThumbRequest): String = fileNameForKey(keyHex(request))

    fun fileNameForKey(keyHex: String): String = keyHex + FILE_SUFFIX

    /** The journal key for [request]: pure hex, so it doubles as a safe file stem. */
    fun keyHex(request: ThumbRequest): String = sha256Hex(keyMaterial(request))

    private fun keyMaterial(request: ThumbRequest): String =
        "${request.sourceId}$SEPARATOR${request.pageIndex}$SEPARATOR${request.widthBucket}"

    fun sha256Hex(input: String): String {
        // java.security ships on every Android device: no digest dependency to add or audit.
        val digest = MessageDigest.getInstance(ALGORITHM).digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * HEX_WIDTH) {
            for (byte in digest) {
                val v = byte.toInt() and BYTE_MASK
                append(HEX_DIGITS[v shr HIGH_SHIFT])
                append(HEX_DIGITS[v and LOW_MASK])
            }
        }
    }
}
