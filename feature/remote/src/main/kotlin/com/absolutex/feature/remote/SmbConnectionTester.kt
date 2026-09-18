package com.absolutex.feature.remote

import com.absolutex.remote.smb.SmbConnection
import com.absolutex.remote.smb.SmbLocation
import com.absolutex.remote.smb.SmbjConnector
import com.absolutex.remote.sync.ConnectionResult
import com.absolutex.remote.sync.mapProbeFailure
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import java.io.IOException
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/**
 * Live connection test for an SMB record, for the servers form's test-then-save.
 *
 * Lives here (not in `:remote:smb`) because the result contract lives in `:remote:sync`,
 * and neither transport module may depend on the other. Credentials ride as parameters:
 * the form tests before anything is saved. The probe connects, authenticates and opens
 * the share, then closes — one logon, no reads.
 *
 * Status mapping, verified against smbj-0.15.0 (javap over the Central jar, plus a jshell
 * probe of the [NtStatus] codes — never from memory): a dead or refused socket surfaces
 * as unchecked `SMBRuntimeException`, an invalidated session or refused share as
 * `SMBApiException` carrying the NT status. Anything unrecognised reads as Unreachable:
 * the test failed either way, and retrying is always safe.
 */
class SmbConnectionTester @Inject constructor() {

    fun test(
        location: SmbLocation,
        password: CharArray,
        connect: (CharArray) -> SmbConnection = { secret -> SmbjConnector(location).connect(secret) },
    ): ConnectionResult {
        val failure = runCatching { connect(password).close() }.exceptionOrNull()
        if (failure is CancellationException) throw failure
        return if (failure == null) ConnectionResult.Ok else mapSmbFailure(failure)
    }
}

/**
 * Maps an SMB connect failure to a result. Auth-adjacent refusals read as AuthFailed (the
 * user-facing fix is always credentials or share permissions); a missing share reads as
 * NotFound; TLS/signing refusals as SecurityRefused.
 */
internal fun mapSmbFailure(e: Throwable): ConnectionResult {
    return when (val hit = firstSmbCause(e)) {
        is SMB2GuestSigningRequiredException,
        is SSLException,
        -> ConnectionResult.SecurityRefused
        is SMBApiException -> when (hit.status) {
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_ACCESS_DENIED,
            -> ConnectionResult.AuthFailed
            NtStatus.STATUS_BAD_NETWORK_NAME -> ConnectionResult.NotFound
            else -> ConnectionResult.Unreachable
        }
        else -> mapProbeFailure(IOException("smb connect failed", e))
    }
}

private fun firstSmbCause(e: Throwable): Throwable? {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is SMB2GuestSigningRequiredException || cause is SSLException || cause is SMBApiException) {
            return cause
        }
        cause = cause.cause
    }
    return null
}
