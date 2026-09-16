package com.absolutex.remote.sync

/**
 * Connection-test contract for a stored [RemoteServer] (sealed interface in RemoteServers.kt,
 * same package — every variant carries at least `id: String`).
 *
 * Implementations live with the transports once #9 (SMB) / #28 (FTP) merge; this module only
 * defines the seam so the servers UI can test-then-save without depending on transports yet.
 *
 * [ConnectionResult.Failed.message] is user-facing and must stay generic: no credentials, no
 * stack traces, no raw paths. Hostnames are user-typed and fine to echo.
 */
interface ConnectionTest {
    suspend fun test(server: RemoteServer): ConnectionResult
}

/** Outcome of [ConnectionTest.test]: reachable ([Ok]) or not ([Failed] with a generic reason). */
sealed interface ConnectionResult {
    data object Ok : ConnectionResult
    data class Failed(val message: String) : ConnectionResult
}
