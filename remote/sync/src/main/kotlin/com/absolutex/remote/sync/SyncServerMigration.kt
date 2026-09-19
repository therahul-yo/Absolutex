package com.absolutex.remote.sync

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.legacySyncServersStore by preferencesDataStore(name = "sync_servers")

/**
 * Runs the one-time sync-servers import into [RemoteServers] (see
 * [RemoteServers.importLegacySyncServers]). Safe to call on every launch: with no legacy
 * document it returns immediately, and a completed import leaves nothing behind to redo.
 * Called from AbsolutexApp.onCreate alongside the sync controller wiring.
 */
suspend fun migrateLegacySyncServers(context: Context) {
    RemoteServers(context).importLegacySyncServers(context.legacySyncServersStore)
}
