package com.absolutex.feature.reader

import android.content.Context
import android.net.Uri
import com.absolutex.source.libarchive.ArchivePasswordException
import com.absolutex.source.libarchive.LibArchiveSource
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
internal fun Context.openArchive(uri: Uri, password: String?): Closeable =
    if (password == null) {
        LibArchiveSource.open { openDescriptor(uri) }
    } else {
        withPasswordChars(password) { chars -> LibArchiveSource.open(chars) { openDescriptor(uri) } }
    }

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
