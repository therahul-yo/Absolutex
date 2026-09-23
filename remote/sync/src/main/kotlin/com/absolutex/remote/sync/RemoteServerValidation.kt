package com.absolutex.remote.sync

private const val MIN_PORT = 1
private const val MAX_PORT = 65535

/**
 * Validates a base URL for storage. Null means valid. HTTPS is required unless the user
 * explicitly allowed cleartext for that host — and the flag travels with the record so a
 * backup restore cannot silently widen it. (The manifest already carries INTERNET for sync;
 * per-host cleartext beyond the platform default fails closed at connect time.)
 */
fun validateServerUrl(baseUrl: String, allowCleartext: Boolean): String? {
    if (baseUrl.isBlank()) return "URL is blank"
    if (baseUrl.length > MAX_URL_LENGTH) return "URL too long"
    val uri = try {
        java.net.URI(baseUrl.trim())
    } catch (e: IllegalArgumentException) {
        return "URL does not parse (${e.message})"
    } catch (e: java.net.URISyntaxException) {
        return "URL does not parse (${e.message})"
    }
    val host = uri.host
    if (host.isNullOrBlank()) return "URL has no host"
    if (host.length > MAX_HOST_LENGTH) return "URL host too long"
    if (uri.port != -1 && uri.port !in MIN_PORT..MAX_PORT) return "URL port out of range: ${uri.port}"
    return when (uri.scheme?.lowercase()) {
        "https" -> null
        "http" -> if (allowCleartext) null else "plain HTTP needs the per-host cleartext opt-in"
        else -> "URL must be http(s)"
    }
}

// Field lengths: persistence sanity, not protocol limits — a 100 000-character value must
// never reach the store. Hosts follow the 253-octet DNS cap; the rest are round, generous
// ceilings far above any real server record.
private const val MAX_HOST_LENGTH = 253
private const val MAX_NAME_LENGTH = 256
private const val MAX_PATH_LENGTH = 4096
private const val MAX_URL_LENGTH = 2048

/**
 * Validates an SMB record for storage. Null means valid. Mirrors SmbLocation's require-rules:
 * blank host/share/path/username, a port outside 1..65535, and any ".." path segment.
 */
fun validateSmb(server: SmbServer): String? {
    if (server.host.isBlank()) return "SMB host must not be blank"
    if (server.share.isBlank()) return "SMB share must not be blank"
    if (server.path.isBlank()) return "SMB path must not be blank"
    if (server.username.isBlank()) return "SMB username must not be blank"
    if (server.port !in MIN_PORT..MAX_PORT) return "SMB port out of range: ${server.port}"
    if (hasParentEscape(server.path)) return "SMB path must not escape its root: ${server.path}"
    if (server.host.length > MAX_HOST_LENGTH) return "SMB host too long"
    if (server.share.length > MAX_NAME_LENGTH) return "SMB share too long"
    if (server.path.length > MAX_PATH_LENGTH) return "SMB path too long"
    if (server.username.length > MAX_NAME_LENGTH) return "SMB username too long"
    return null
}

/**
 * Validates an FTP record for storage. Null means valid. Mirrors FtpLocation's require-rules
 * (blank host/path/username, port range, no ".." path segment).
 *
 * The useTls-vs-allowCleartext combination is deliberately NOT validated here: plain FTP with
 * allowCleartext=false stays storable and fails closed at connect time instead, so a record
 * restored from a backup is never refused by the store over a policy call the store cannot make.
 */
fun validateFtp(server: FtpServer): String? {
    if (server.host.isBlank()) return "FTP host must not be blank"
    if (server.port !in MIN_PORT..MAX_PORT) return "FTP port out of range: ${server.port}"
    if (server.username.isBlank()) return "FTP username must not be blank"
    if (server.path.isBlank()) return "FTP path must not be blank"
    if (hasParentEscape(server.path)) return "FTP path must not escape its root: ${server.path}"
    if (server.host.length > MAX_HOST_LENGTH) return "FTP host too long"
    if (server.username.length > MAX_NAME_LENGTH) return "FTP username too long"
    if (server.path.length > MAX_PATH_LENGTH) return "FTP path too long"
    return null
}

private fun hasParentEscape(path: String): Boolean =
    path.replace('\\', '/').split('/').any { it == ".." }

/** Trims whitespace and trailing slashes; a bare root ("/") is kept, never emptied. */
internal fun normalisePath(path: String): String {
    val trimmed = path.trim()
    val stripped = trimmed.trimEnd('/', '\\')
    if (stripped.isEmpty()) return trimmed
    return stripped
}
