package com.absolutex.feature.remote

import com.absolutex.remote.ftp.CommonsNetFtpTransport
import com.absolutex.remote.ftp.FtpLocation
import com.absolutex.remote.sync.ConnectionResult
import com.absolutex.remote.sync.mapProbeFailure
import java.io.IOException

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
class FtpConnectionProbe {

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
 */
internal fun mapFtpFailure(e: IOException): ConnectionResult = when {
    e.message?.startsWith("FTP login refused") == true -> ConnectionResult.AuthFailed
    e.message?.startsWith("cannot list FTP path") == true -> ConnectionResult.NotFound
    else -> mapProbeFailure(e)
}
