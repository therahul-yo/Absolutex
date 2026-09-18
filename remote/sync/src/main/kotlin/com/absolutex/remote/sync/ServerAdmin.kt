package com.absolutex.remote.sync

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-list mutations that span two stores: the [RemoteServers] record and the
 * [SyncSecrets] Keystore entries for the same id. Removing a server wipes all four of its
 * secrets and none of any other server's — call this, never the two stores separately, so a
 * future caller cannot remove one and forget the other.
 */
@Singleton
class ServerAdmin @Inject constructor(
    private val servers: RemoteServers,
    private val secrets: SyncSecrets,
) {
    suspend fun removeServer(id: String) {
        servers.remove(id)
        secrets.clearServer(id)
    }
}
