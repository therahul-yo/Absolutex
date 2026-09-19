package com.absolutex.feature.remote

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.browseHistoryFile by preferencesDataStore(name = "remote_browse")

/**
 * Last folder per server, remembered across launches. A small prefs file of its own —
 * server records stay identity-only, so browsing never rewrites them (and never widens
 * what a backup restore carries). A stored path that fails today's validation (relative,
 * escaping, absurdly long) reads back as absent: the browser falls back to the record
 * root instead of opening a folder the validators would never have stored.
 */
@Singleton
class BrowseHistoryStore @Inject constructor(
    private val store: DataStore<Preferences>,
) {

    suspend fun lastDir(serverId: String): String? {
        val raw = store.data.map { it[keyFor(serverId)] }.first()
        return if (raw != null && isBrowsablePath(raw)) raw else null
    }

    suspend fun saveDir(serverId: String, path: String) {
        if (!isBrowsablePath(path)) return
        store.edit { it[keyFor(serverId)] = path }
    }

    private fun keyFor(serverId: String) = stringPreferencesKey(KEY_PREFIX + serverId)

    private companion object {
        const val KEY_PREFIX = "last_dir_"
        const val MAX_PATH_LENGTH = 4096

        fun isBrowsablePath(path: String): Boolean {
            if (!path.startsWith("/")) return false
            if (path.length > MAX_PATH_LENGTH) return false
            return path.split('/').none { it == ".." }
        }
    }
}
