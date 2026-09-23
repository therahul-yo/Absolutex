package com.absolutex.remote.smb

import com.absolutex.remote.core.CredentialExpiredException
import com.absolutex.remote.core.TransportAuthException
import com.absolutex.remote.core.TransportPermanentException
import com.absolutex.remote.core.TransientTransportException
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import java.io.FileNotFoundException
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Failure mapping for the SMB transport, file-private in spirit but top-level in fact:
 * SmbjTransport is at its function budget, and these are pure over their arguments
 * (statuses, paths, credential alias, staleness probes), so they live here where unit
 * tests can also pin the status→type table directly.
 *
 * Cross-call staleness rides in lambdas: a mid-session logon failure marks rotation,
 * and a later connect failure reads it back to tell "wrong from the start" apart.
 */
internal fun mapConnectFailure(
    e: IOException,
    host: String,
    credentialAlias: String?,
    isStaleCredentials: () -> Boolean,
): IOException {
    val status = firstSmbStatus(e)
    if (status == NtStatus.STATUS_LOGON_FAILURE) {
        return if (isStaleCredentials()) {
            CredentialExpiredException("smb credentials rejected for $host", e, credentialAlias)
        } else {
            TransportAuthException("smb authentication failed for $host", e)
        }
    }
    if (status == NtStatus.STATUS_ACCESS_DENIED) {
        return TransportAuthException("smb authentication failed for $host", e)
    }
    if (hasSmbCause<SMB2GuestSigningRequiredException>(e) || hasSmbCause<SSLException>(e)) {
        return TransportPermanentException("smb security refused for $host", e)
    }
    return e
}

internal fun firstSmbStatus(e: Throwable): NtStatus? {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is SMBApiException) return cause.status
        cause = cause.cause
    }
    return null
}

internal inline fun <reified T : Throwable> hasSmbCause(error: Throwable): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is T) return true
        cause = cause.cause
    }
    return false
}

/**
 * Types an unchecked SMBJ failure at the only site that sees the NT status. A
 * mid-session logon failure is rotation (the session was good, the password
 * changed); missing objects are missing files; denied handles are permissions —
 * none retry. Everything else is a dead socket or session and earns the retry.
 */
internal fun mapSmbReadFailure(
    message: String,
    remotePath: String,
    e: Throwable,
    credentialAlias: String?,
    markStaleCredentials: () -> Unit,
): IOException {
    val typed: IOException? = when (firstSmbStatus(e)) {
        NtStatus.STATUS_LOGON_FAILURE -> {
            markStaleCredentials()
            CredentialExpiredException("smb credentials rejected for $remotePath", e, credentialAlias)
        }
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NO_SUCH_FILE,
        NtStatus.STATUS_BAD_NETWORK_NAME,
        NtStatus.STATUS_NOT_FOUND,
        -> FileNotFoundException("smb object not found: $remotePath").apply { initCause(e) }
        NtStatus.STATUS_ACCESS_DENIED ->
            TransportPermanentException("smb access denied: $remotePath", e)
        else -> null
    }
    return typed ?: TransientTransportException(message, e)
}

// SMBJ's failure surface is unchecked by design: Share.receive rethrows a dead socket as
// SMBRuntimeException and an invalidated session or server-closed file as SMBApiException,
// both RuntimeException. The seam promises IOException, and the retry only sees
// IOException — so the unchecked surface is wrapped here, where the SMBJ calls happen.
@Suppress("TooGenericExceptionCaught")
internal fun smbReadLength(
    share: SmbConnection,
    remotePath: String,
    credentialAlias: String?,
    markStaleCredentials: () -> Unit,
): Long {
    try {
        share.openFile(remotePath).use { return it.length }
    } catch (e: IOException) {
        throw e
    } catch (e: RuntimeException) {
        throw mapSmbReadFailure("smb stat failed", remotePath, e, credentialAlias, markStaleCredentials)
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun smbReadOnce(
    share: SmbConnection,
    remotePath: String,
    offset: Long,
    length: Int,
    credentialAlias: String?,
    markStaleCredentials: () -> Unit,
): ByteArray {
    try {
        // Fresh handle per call, so concurrent readers never share a file offset.
        share.openFile(remotePath).use { file ->
            return drainSmbHandle(file, offset, length)
        }
    } catch (e: Exception) {
        // Genuine IOExceptions pass through unwrapped, so the suppressed chains stay
        // clean; an interrupt keeps its exact old shape (never transient, never
        // retried); anything else is the unchecked surface above, mapped for retry.
        if (e is InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("smb read failed", e)
        }
        throw e as? IOException ?: mapSmbReadFailure(
            "smb read failed",
            remotePath,
            e,
            credentialAlias,
            markStaleCredentials,
        )
    }
}

/** Drains exactly [length] bytes: File.read may return fewer bytes than asked. */
private fun drainSmbHandle(file: RemoteFileHandle, offset: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    var done = 0
    // A short stream is EOF mid-transfer: transient, safe to resume.
    while (done < length) {
        val got = file.read(out, offset + done, done, length - done)
        if (got <= 0) throw TransientTransportException("short read at $offset ($done of $length bytes)")
        done += got
    }
    return out
}
