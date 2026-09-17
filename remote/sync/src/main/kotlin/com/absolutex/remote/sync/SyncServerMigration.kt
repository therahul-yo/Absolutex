package com.absolutex.remote.sync

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.legacySyncServersStore by preferencesDataStore(name = "sync_servers")

/**
 * Runs the one-time sync-servers import into [RemoteServers] (see
 * [RemoteServers.importLegacySyncServers]). Safe to call on every launch: with no legacy
 * document it returns immediately, and a completed import leaves nothing behind to redo.
 *
 * TODO(agent3): call once from app startup (AbsolutexApp.onCreate or the first composition)
 * alongside the sync controller wiring — this module owns neither the Application nor the
 * NavHost, so the call site lives outside it.
 */
suspend fun migrateLegacySyncServers(context: Context) {
    RemoteServers(context).importLegacySyncServers(context.legacySyncServersStore)
}
