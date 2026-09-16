package com.absolutex.remote.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.syncServersStore by preferencesDataStore(name = "sync_servers")

/**
 * One Komga or Kavita server for read-progress sync. Secrets never live here — only the
 * username (non-secret) and which secret to load; see [SyncSecrets].
 */
data class SyncServer(
    val id: String,
    val kind: ServerKind,
    val baseUrl: String,
    val allowCleartext: Boolean = false,
    val username: String? = null,
    val usesApiKey: Boolean = false,
)

/**
 * Validates a base URL for storage. Null means valid. HTTPS is required unless the user
 * explicitly allowed cleartext for that host — and the flag travels with the record so a
 * backup restore cannot silently widen it. (Enforcement past validation — the per-host
 * network security config — is milestone 3's manifest work; until then cleartext hosts fail
 * closed at connect time on modern Android.)
 */
fun validateServerUrl(baseUrl: String, allowCleartext: Boolean): String? {
    if (baseUrl.isBlank()) return "URL is blank"
    val uri = try {
        java.net.URI(baseUrl.trim())
    } catch (e: IllegalArgumentException) {
        return "URL does not parse (${e.message})"
    } catch (e: java.net.URISyntaxException) {
        return "URL does not parse (${e.message})"
    }
    if (uri.host.isNullOrBlank()) return "URL has no host"
    return when (uri.scheme?.lowercase()) {
        "https" -> null
        "http" -> if (allowCleartext) null else "plain HTTP needs the per-host cleartext opt-in"
        else -> "URL must be http(s)"
    }
}

/**
 * Persisted Komga/Kavita server list (DataStore, one JSON document). No UI in this
 * milestone — milestone 3's servers screen manages these records; the sync controller below
 * only reads them. Keyed by caller-assigned ids (UUIDs from the future UI).
 */
class SyncServers(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.syncServersStore)

    val servers: Flow<List<SyncServer>> = store.data.map { prefs ->
        val raw = prefs[SERVERS_KEY] ?: return@map emptyList()
        parseServers(raw)
    }

    suspend fun current(): List<SyncServer> = servers.first()

    suspend fun save(server: SyncServer) {
        val error = validateServerUrl(server.baseUrl, server.allowCleartext)
        if (error != null) throw IllegalArgumentException(error)
        store.edit { prefs ->
            val kept = parseServers(prefs[SERVERS_KEY]).filterNot { it.id == server.id }
            prefs[SERVERS_KEY] = serialise(kept + normalised(server))
        }
    }

    suspend fun remove(id: String) {
        store.edit { prefs ->
            prefs[SERVERS_KEY] = serialise(parseServers(prefs[SERVERS_KEY]).filterNot { it.id == id })
        }
    }

    private fun normalised(server: SyncServer): SyncServer =
        server.copy(baseUrl = server.baseUrl.trim().trimEnd('/'))

    private fun serialise(servers: List<SyncServer>): String = JSONArray(
        servers.map { server ->
            JSONObject()
                .put("id", server.id)
                .put("kind", server.kind.name)
                .put("baseUrl", server.baseUrl)
                .put("allowCleartext", server.allowCleartext)
                .put("username", server.username)
                .put("usesApiKey", server.usesApiKey)
        },
    ).toString()

    private fun parseServers(raw: String?): List<SyncServer> {
        // One bad record (renamed kind, torn write) is skipped, never fatal to its neighbours.
        val array = runCatching { JSONArray(raw ?: "") }.getOrNull() ?: return emptyList()
        return List(array.length(), array::getJSONObject).mapNotNull { obj ->
            val id = optString(obj, "id") ?: return@mapNotNull null
            val kind = optString(obj, "kind")?.let { name ->
                ServerKind.entries.firstOrNull { it.name == name }
            } ?: return@mapNotNull null
            val baseUrl = optString(obj, "baseUrl") ?: return@mapNotNull null
            SyncServer(
                id = id,
                kind = kind,
                baseUrl = baseUrl,
                allowCleartext = obj.optBoolean("allowCleartext"),
                username = optString(obj, "username"),
                usesApiKey = obj.optBoolean("usesApiKey"),
            )
        }
    }

    companion object {
        private val SERVERS_KEY = stringPreferencesKey("servers")
    }
}
