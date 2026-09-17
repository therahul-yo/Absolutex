package com.absolutex.remote.sync

import java.io.IOException

/**
 * Connection-test contract for a stored [RemoteServer].
 *
 * The result carries no message: the callers that display it (the servers UI) map each
 * variant to their own string resources, so no English, banner, path or credential ever
 * leaks through this seam. Hostnames are user-typed and safe for the UI to echo itself.
 */
interface ConnectionTest {
    suspend fun test(server: RemoteServer): ConnectionResult
}

/** Outcome of [ConnectionTest.test]. */
sealed interface ConnectionResult {
    /** Reachable, authenticated, and speaking the expected protocol. */
    data object Ok : ConnectionResult

    /** Reachable, but the credentials (or API key) were refused. */
    data object AuthFailed : ConnectionResult

    /** No route to the host: DNS, refused, or timed out. */
    data object Unreachable : ConnectionResult

    /** Connected, but TLS negotiation or the signing/encryption requirement failed. */
    data object SecurityRefused : ConnectionResult

    /** Authenticated, but the share, path, or API surface is not there. */
    data object NotFound : ConnectionResult
}

/**
 * Maps a transport failure to a result. Status-bearing failures are mapped by callers that
 * see the code; everything arriving here is a bare IOException: a TLS signal anywhere in the
 * cause chain reads as [ConnectionResult.SecurityRefused], while connectivity signals
 * (UnknownHost, refused, no route, timeouts, closed sockets — and anything else, since a
 * server that cannot complete the probe fails the test) read as [ConnectionResult.Unreachable].
 */
internal fun mapProbeFailure(e: IOException): ConnectionResult {
    return if (hasCause<javax.net.ssl.SSLException>(e)) {
        ConnectionResult.SecurityRefused
    } else {
        ConnectionResult.Unreachable
    }
}

private inline fun <reified T : Throwable> hasCause(error: Throwable): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is T) return true
        cause = cause.cause
    }
    return false
}
