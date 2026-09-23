package com.absolutex.feature.remote

import com.absolutex.remote.ftp.CommonsNetFtpTransport
import com.absolutex.remote.ftp.FtpLocation
import com.absolutex.remote.sync.ConnectionResult
import com.absolutex.remote.sync.mapProbeFailure
import java.io.IOException
import javax.inject.Inject

/**
 * Live connection test for an FTP/FTPS record, for the servers form's test-then-save.
 *
 * Lives here (not in `:remote:ftp`) because the result contract lives in `:remote:sync`,
 * and neither transport module may depend on the other without dragging its native weight
 * into already-shipped modules. Credentials ride as parameters: the form tests before
 * anything is saved. The probe lists the configured path — reachability, auth and path in
 * one call, with an empty folder correctly reading as Ok — and maps with the shared
 * [mapProbeFailure] rules.
 */
class FtpConnectionProbe @Inject constructor() {

    fun test(location: FtpLocation, password: CharArray): ConnectionResult {
        val secret = password.copyOf()
        try {
            CommonsNetFtpTransport(location, { secret.copyOf() }).use { transport ->
                transport.listDir(location.path)
            }
            return ConnectionResult.Ok
        } catch (e: IOException) {
            return mapFtpFailure(e)
        } finally {
            secret.fill(Char.MIN_VALUE)
        }
    }
}

/**
 * Totally-ordered mapping: the transport's own failure messages first (same release train,
 * so the prefixes are versioned together), then the shared connectivity/TLS rules.
 *
 * Messages are matched through the cause chain, not just the outer error: bounded retry
 * wraps exhaustion in TransientExhaustedException (cause = last attempt), and a missing
 * path must still read as NotFound from inside that wrapper.
 */
internal fun mapFtpFailure(e: IOException): ConnectionResult = when {
    mentions(e, "FTP login refused") -> ConnectionResult.AuthFailed
    mentions(e, "cannot list FTP path") -> ConnectionResult.NotFound
    else -> mapProbeFailure(e)
}

private fun mentions(error: Throwable, prefix: String): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause.message?.startsWith(prefix) == true) return true
        cause = cause.cause
    }
    return false
}
