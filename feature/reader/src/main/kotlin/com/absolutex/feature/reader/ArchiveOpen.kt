package com.absolutex.feature.reader

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import com.absolutex.source.libarchive.ArchivePasswordException
import com.absolutex.source.libarchive.LibArchiveSource
import com.absolutex.source.libarchive.SolidCacheConfig
import com.absolutex.source.libarchive.SolidCacheKey
import com.absolutex.source.libarchive.WrongPasswordException
import java.io.Closeable

// Split out of OpenBook.kt, which is at detekt's TooManyFunctions ceiling.

/**
 * The archive reader, with [password] when one was offered. Encrypted archives fail here with an
 * [ArchivePasswordException], which the reader turns into the password prompt.
 *
 * The password is converted to a CharArray only for the duration of the call and wiped after it
 * ([withPasswordChars]); [LibArchiveSource] takes its own UTF-8 copy and keeps it only for an
 * encrypted book, until close. The incoming String cannot be wiped — see [BookOpener].
 */
internal fun Context.openArchive(uri: Uri, password: String?, cacheSolid: Boolean = false): Closeable {
    val cache = if (cacheSolid) solidCacheFor(uri) else null
    return if (password == null) {
        LibArchiveSource.open(null, cache) { openDescriptor(uri) }
    } else {
        withPasswordChars(password) { chars -> LibArchiveSource.open(chars, cache) { openDescriptor(uri) } }
    }
}

/**
 * Where a solid 7z's decode-once cache goes, or null to read the book directly.
 *
 * Only the reader asks for it ([cacheSolid]): covers and scans open many books and must never
 * start a background decode of each. The key hashes the Uri with the file's size and modification
 * time, so a replaced file gets a fresh cache and nothing readable (a name, a folder) reaches the
 * disk or a log. Anything that is not a plain file (a pipe, a provider that streams) has no size
 * or time worth keying on, so it is read directly, as is any descriptor that cannot be examined.
 */
private fun Context.solidCacheFor(uri: Uri): SolidCacheConfig? = runCatching {
    openDescriptor(uri).use { pfd ->
        val stat = Os.fstat(pfd.fileDescriptor)
        val key = SolidCacheKey.of(uri.toString(), stat.st_size, stat.st_mtime * MILLIS_PER_SECOND)
        SolidCacheConfig(cacheDir, key).takeIf { OsConstants.S_ISREG(stat.st_mode) }
    }
}.getOrNull()

private const val MILLIS_PER_SECOND = 1000L

/**
 * Runs [block] with [password] as a CharArray that is zeroed afterwards, even when [block] throws.
 *
 * A password the archive API cannot take (empty, or containing NUL: libarchive's C-string
 * contract) is refused as a wrong password without reaching it, so the prompt says "incorrect"
 * instead of the open failing with an unrelated error.
 */
internal fun <T> withPasswordChars(password: String, block: (CharArray) -> T): T {
    if (password.isEmpty() || '\u0000' in password) throw WrongPasswordException("password rejected")
    val chars = password.toCharArray()
    try {
        return block(chars)
    } finally {
        chars.fill('\u0000')
    }
}
