package com.absolutex.remote.ftp

/**
 * Password vault for FTP logins, keyed by an app-chosen label (e.g. the location's [FtpLocation.uri]).
 *
 * Passwords travel as [CharArray] so owners can zero them after use; implementations must never
 * persist plaintext — see [InMemoryFtpCredentialStore] (memory only) and
 * [KeyStoreFtpCredentialStore] (AES/GCM via AndroidKeyStore).
 */
interface FtpCredentialStore {
    /** Stores a copy; the caller keeps — and should zero — the original. */
    fun save(key: String, password: CharArray)

    /** A fresh copy of the stored password, or null. The caller owns the returned array. */
    fun load(key: String): CharArray?

    /** Removes the entry and zeroes the stored copy. */
    fun clear(key: String)
}
