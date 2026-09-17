package com.absolutex.remote.smb

/**
 * An SMB book location. Every identity field is required — no defaults — so a half-filled
 * location cannot silently address the wrong server (cf. the TileKey.bookId="" incident).
 */
data class SmbLocation(
    val host: String,
    val share: String,
    val path: String,
    val port: Int,
    val username: String,
    /**
     * Explicit opt-out for guest and legacy shares: dialects fall back to 2.1+ and signing
     * and encryption stop being required. Default is SMB3 with both required, so a
     * plaintext-swap on the path cannot reach the ZIP parser or the image decoders.
     */
    val allowUnsigned: Boolean = false,
) {
    init {
        require(host.isNotBlank()) { "smb host is blank" }
        require(share.isNotBlank()) { "smb share is blank" }
        require(path.isNotBlank()) { "smb path is blank" }
        require(port in 1..MAX_PORT) { "smb port out of range: $port" }
        require(username.isNotBlank()) { "smb username is blank" }
        // Server-controlled ".." must never escape the share when we resolve entry names.
        require(path.split('/').none { it == ".." }) { "smb path escapes its share" }
    }

    companion object {
        const val MAX_PORT = 65535
    }
}
