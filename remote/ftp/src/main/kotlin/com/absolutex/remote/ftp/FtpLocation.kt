package com.absolutex.remote.ftp

/**
 * Address of one archive on an FTP/FTPS server. Every field is required: defaults on identity
 * fields caused the TileKey.bookId incident, so there are none here — callers pass
 * [DEFAULT_FTP_PORT] or [DEFAULT_FTPS_PORT] explicitly instead of getting one silently.
 *
 * Plain FTP sends the username and password in the clear. It stays supported because the brief
 * lists FTP, not because it is safe — prefer [useTls] (FTPS) whenever the server offers it.
 */
data class FtpLocation(
    val host: String,
    val port: Int,
    val username: String,
    val path: String,
    val useTls: Boolean,
) {
    init {
        require(host.isNotBlank()) { "FTP host must not be blank" }
        require(port in MIN_PORT..MAX_PORT) { "FTP port out of range: $port" }
        require(username.isNotBlank()) { "FTP username must not be blank" }
        require(path.isNotBlank()) { "FTP path must not be blank" }
        val segments = path.replace(BACKSLASH, SLASH).split(SLASH)
        require(segments.none { it == PARENT_SEGMENT }) { "FTP path must not escape its root: $path" }
    }

    /** `ftps` when [useTls], `ftp` otherwise. */
    val scheme: String
        get() = if (useTls) SCHEME_FTPS else SCHEME_FTP

    /** Human-readable address. Carries the username but never a password. */
    val uri: String
        get() = "$scheme://$username@$host:$port$path"

    companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        /** Explicit-FTPS default (the `FTPSClient(false)` AUTH-upgrade path, not implicit). */
        const val DEFAULT_FTPS_PORT = 990
        const val DEFAULT_FTP_PORT = 21
        private const val BACKSLASH = '\\'
        private const val SLASH = '/'
        private const val PARENT_SEGMENT = ".."
        private const val SCHEME_FTP = "ftp"
        private const val SCHEME_FTPS = "ftps"
    }
}
