package com.absolutex.remote.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.absolutex.remote.core.CredentialExpiredException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.io.IOException
import org.json.JSONObject

private val Context.remoteServersStore by preferencesDataStore(name = "remote_servers")

/**
 * Which remote backend a persisted server record talks to: an SMB share, an FTP/FTPS drop,
 * or a Komga/Kavita instance for read-progress sync.
 */
enum class RemoteKind {
    SMB,
    FTP,
    KOMGA,
    KAVITA,
}

// Field-length ceilings live with the validators (RemoteServerValidation.kt).

/**
 * One persisted remote server for the future servers screen. Secrets never live here — only
 * non-secret identity fields; passwords and API keys stay in their credential stores.
 */
sealed interface RemoteServer {
    val id: String
    val kind: RemoteKind
}

/**
 * One SMB share plus the path inside it to read from.
 *
 * Credential rotation (the NAS password changes mid-session) surfaces from the
 * transports as [CredentialExpiredException] — never retried, never wrapped — and the
 * fix is re-authentication, not a retry button. The servers screen owns that prompt;
 * see [credentialExpiredServerId] for the typed seam.
 */
data class SmbServer(
    override val id: String,
    val host: String,
    val share: String,
    val path: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val allowUnsigned: Boolean = false,
) : RemoteServer {
    override val kind: RemoteKind = RemoteKind.SMB

    companion object {
        const val DEFAULT_PORT = 445
    }
}

/**
 * One FTP/FTPS drop. Plain FTP sends credentials in the clear — prefer [useTls] (FTPS)
 * whenever the server offers it.
 *
 * Same rotation contract as [SmbServer]: mid-session refusal is
 * [CredentialExpiredException], and re-authentication is the servers screen's job
 * ([credentialExpiredServerId]).
 */
data class FtpServer(
    override val id: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val path: String,
    val username: String,
    val useTls: Boolean,
    val allowCleartext: Boolean = false,
) : RemoteServer {
    override val kind: RemoteKind = RemoteKind.FTP

    companion object {
        const val DEFAULT_PORT = 21
    }
}

/** One Komga instance for read-progress sync. */
data class KomgaServer(
    override val id: String,
    val baseUrl: String,
    val allowCleartext: Boolean = false,
    val username: String? = null,
    val usesApiKey: Boolean = false,
) : RemoteServer {
    override val kind: RemoteKind = RemoteKind.KOMGA
}

/** One Kavita instance for read-progress sync. Same fields as [KomgaServer]. */
data class KavitaServer(
    override val id: String,
    val baseUrl: String,
    val allowCleartext: Boolean = false,
    val username: String? = null,
    val usesApiKey: Boolean = false,
) : RemoteServer {
    override val kind: RemoteKind = RemoteKind.KAVITA
}

private const val SMB_FIELD_COUNT = 4

private fun parseSmb(obj: JSONObject, id: String): SmbServer? {
    val fields = listOfNotNull(
        optString(obj, "host"),
        optString(obj, "share"),
        optString(obj, "path"),
        optString(obj, "username"),
    )
    if (fields.size != SMB_FIELD_COUNT) return null
    return SmbServer(
        id = id,
        host = fields[0],
        share = fields[1],
        path = fields[2],
        port = obj.optInt("port", SmbServer.DEFAULT_PORT),
        username = fields[3],
        allowUnsigned = obj.optBoolean("allowUnsigned"),
    )
}

private fun parseFtp(obj: JSONObject, id: String): FtpServer? {
    val host = optString(obj, "host")
    val path = optString(obj, "path")
    val username = optString(obj, "username")
    if (host == null || path == null || username == null) return null
    return FtpServer(
        id = id,
        host = host,
        port = obj.optInt("port", FtpServer.DEFAULT_PORT),
        path = path,
        username = username,
        useTls = obj.optBoolean("useTls"),
        allowCleartext = obj.optBoolean("allowCleartext"),
    )
}

private fun parseKomga(obj: JSONObject, id: String): KomgaServer? {
    val baseUrl = optString(obj, "baseUrl")
    if (baseUrl == null) return null
    return KomgaServer(
        id = id,
        baseUrl = baseUrl,
        allowCleartext = obj.optBoolean("allowCleartext"),
        username = optString(obj, "username"),
        usesApiKey = obj.optBoolean("usesApiKey"),
    )
}

private fun parseKavita(obj: JSONObject, id: String): KavitaServer? {
    val baseUrl = optString(obj, "baseUrl")
    if (baseUrl == null) return null
    return KavitaServer(
        id = id,
        baseUrl = baseUrl,
        allowCleartext = obj.optBoolean("allowCleartext"),
        username = optString(obj, "username"),
        usesApiKey = obj.optBoolean("usesApiKey"),
    )
}

/**
 * Re-auth seam for credential rotation (Phase F transport hardening).
 *
 * Returns the server id carried by a [CredentialExpiredException], or null for any
 * other failure. The transports know the credential alias (which the backends set to
 * the server id); FTP logins do not carry it, so callers there fall back to the
 * server id they resolved the transport for — either way the value returned here is
 * the record the user must sign in to again.
 *
 * TODO(agent3): the servers screen must catch this at the reader's error path and
 * offer sign-in-again for the returned id (open the server form with its stored
 * record, prompt for the new secret, save to the credential store). Do not build a
 * retry button for it, and do not route it to the generic unreachable error: retrying
 * a rotated password is indistinguishable from guessing, and repeated guesses lock
 * NAS accounts.
 */
fun credentialExpiredServerId(error: IOException): String? =
    (error as? CredentialExpiredException)?.serverId

/**
 * Persisted server list for every remote kind (DataStore, one JSON document). No UI in this
 * milestone — the future servers screen manages these records; keyed by caller-assigned ids.
 *
 * Seam: M5's [SyncServers] stays the sync runtime read-model; a later milestone migrates sync
 * onto this list, at which point SyncServers becomes a view over these records.
 */
class RemoteServers(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.remoteServersStore)

    val servers: Flow<List<RemoteServer>> = store.data.map { prefs ->
        parseServersOrNull(prefs[SERVERS_KEY]) ?: emptyList()
    }

    suspend fun current(): List<RemoteServer> = servers.first()

    suspend fun save(server: RemoteServer) {
        val error = validateOf(server)
        if (error != null) throw IllegalArgumentException(error)
        store.edit { prefs ->
            val kept = keptOrThrow(prefs[SERVERS_KEY]).filterNot { it.id == server.id }
            val unknown = unknownRaws(prefs[SERVERS_KEY]).filterNot { optString(it, "id") == server.id }
            prefs[SERVERS_KEY] = serialiseRaw((kept + normalised(server)).map(::serialiseOne) + unknown)
        }
    }

    suspend fun remove(id: String) {
        store.edit { prefs ->
            val kept = keptOrThrow(prefs[SERVERS_KEY]).filterNot { it.id == id }
            val unknown = unknownRaws(prefs[SERVERS_KEY]).filterNot { optString(it, "id") == id }
            prefs[SERVERS_KEY] = serialiseRaw(kept.map(::serialiseOne) + unknown)
        }
    }



    /**
     * One-time import from the pre-unification sync-servers document (the M5 store that held
     * Komga/Kavita records before this list covered all four kinds). Records keep their ids,
     * so Keystore secrets (keyed by server id) carry over untouched — no server and no
     * secret is lost. Idempotent: ids already here are skipped, so a re-run after a crash
     * resumes instead of duplicating.
     *
     * The legacy document clears only on full success. A record that fails today's
     * validation aborts the import with the legacy intact (nothing is dropped, the re-run
     * retries it) — loud failure beats silent loss for a user's server list.
     */
    suspend fun importLegacySyncServers(legacy: DataStore<Preferences>) {
        val raw = legacy.data.first()[LEGACY_SERVERS_KEY] ?: return
        // Unparseable is not empty: clearing the legacy over a torn document would lose
        // every server with the secrets left orphaned. Keep it, surface the failure, and
        // retry on the next start.
        val incoming = parseLegacyServers(raw)
            ?: throw IOException("legacy sync servers document is corrupt - keeping it for retry")
        val present = current().map { it.id }.toSet()
        for (server in incoming) {
            if (server.id !in present) {
                save(server)
            }
        }
        legacy.edit { prefs -> prefs.remove(LEGACY_SERVERS_KEY) }
    }

    private fun validateOf(server: RemoteServer): String? = when (server) {
        is SmbServer -> validateSmb(server)
        is FtpServer -> validateFtp(server)
        is KomgaServer -> validateServerUrl(server.baseUrl, server.allowCleartext)
        is KavitaServer -> validateServerUrl(server.baseUrl, server.allowCleartext)
    }

    private fun normalised(server: RemoteServer): RemoteServer = when (server) {
        is SmbServer -> server.copy(
            host = server.host.trim(),
            share = server.share.trim(),
            path = normalisePath(server.path),
            username = server.username.trim(),
        )
        is FtpServer -> server.copy(
            host = server.host.trim(),
            path = normalisePath(server.path),
            username = server.username.trim(),
        )
        is KomgaServer -> server.copy(baseUrl = server.baseUrl.trim().trimEnd('/'))
        is KavitaServer -> server.copy(baseUrl = server.baseUrl.trim().trimEnd('/'))
    }

    private fun serialise(servers: List<RemoteServer>): String =
        serialiseRaw(servers.map(::serialiseOne))

    /**
     * Legacy M5 shape: `{id, kind: KOMGA|KAVITA, baseUrl, allowCleartext, username,
     * usesApiKey}`. Anything else (unknown kinds, torn records) is skipped like any other
     * corrupt record — and the import aborts nothing over it.
     */
    private fun parseLegacyServers(raw: String): List<RemoteServer>? {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return List(array.length(), array::getJSONObject).mapNotNull { obj ->
            val id = optString(obj, "id") ?: return@mapNotNull null
            val baseUrl = optString(obj, "baseUrl") ?: return@mapNotNull null
            val allowCleartext = obj.optBoolean("allowCleartext")
            val username = optString(obj, "username")
            val usesApiKey = obj.optBoolean("usesApiKey")
            when (optString(obj, "kind")) {
                ServerKind.KOMGA.name -> KomgaServer(id, baseUrl, allowCleartext, username, usesApiKey)
                ServerKind.KAVITA.name -> KavitaServer(id, baseUrl, allowCleartext, username, usesApiKey)
                else -> null
            }
        }
    }

    companion object {
        // Visible for tests: seeding a corrupt document exercises the fail-closed paths.
        internal val SERVERS_KEY = stringPreferencesKey("servers")

        /** Key inside the legacy `sync_servers` document; frozen since M5, never renamed. */
        private val LEGACY_SERVERS_KEY = stringPreferencesKey("servers")
    }
}

/** Document writer: known records serialised plus unknown ones carried through untouched. */
internal fun serialiseRaw(objects: List<JSONObject>): String =
    JSONArray(objects).toString()

private fun serialiseOne(server: RemoteServer): JSONObject {
    val obj = JSONObject()
        .put("id", server.id)
        .put("kind", server.kind.name)
    when (server) {
        is SmbServer -> obj
            .put("host", server.host)
            .put("share", server.share)
            .put("path", server.path)
            .put("port", server.port)
            .put("username", server.username)
            .put("allowUnsigned", server.allowUnsigned)
        is FtpServer -> obj
            .put("host", server.host)
            .put("port", server.port)
            .put("path", server.path)
            .put("username", server.username)
            .put("useTls", server.useTls)
            .put("allowCleartext", server.allowCleartext)
        is KomgaServer -> obj
            .put("baseUrl", server.baseUrl)
            .put("allowCleartext", server.allowCleartext)
            .put("username", server.username)
            .put("usesApiKey", server.usesApiKey)
        is KavitaServer -> obj
            .put("baseUrl", server.baseUrl)
            .put("allowCleartext", server.allowCleartext)
            .put("username", server.username)
            .put("usesApiKey", server.usesApiKey)
    }
    return obj
}

/**
 * Fail-closed read for writers: a missing document is an empty list, but an unparseable
 * one throws instead of degrading — degrading here would let the very next save persist
 * an empty list over every stored server. Throwing inside the DataStore transformer
 * aborts the edit: nothing is written.
 */
private fun keptOrThrow(raw: String?): List<RemoteServer> {
    if (raw == null) return emptyList()
    return parseServersOrNull(raw)
        ?: throw IOException("servers store is corrupt - refusing to overwrite it")
}

/**
 * Forward-compat channel: records with an id and a kind this build does not know
 * (a newer build's ONEDRIVE/DROPBOX/GDRIVE, read by an older one) are invisible to the
 * typed list but ride through every save/remove byte-identical, so an older build can
 * never silently erase a newer build's records by writing the document back.
 * Torn records (no id, no kind, known-kind-but-malformed) are NOT preserved — those
 * are corruption, not the future, and still drop.
 */
private fun unknownRaws(raw: String?): List<JSONObject> {
    val array = runCatching { JSONArray(raw ?: "") }.getOrNull() ?: return emptyList()
    return List(array.length(), array::getJSONObject).filter { obj ->
        val kindName = optString(obj, "kind")
        optString(obj, "id") != null && kindName != null &&
            RemoteKind.entries.none { it.name == kindName }
    }
}

/**
 * Null means the whole document is unparseable (torn write, truncated file) as opposed
 * to merely containing bad records, which are skipped per record below. Callers that
 * would overwrite the document must treat null as fatal (see [keptOrThrow]); display
 * paths degrade it to empty.
 */
private fun parseServersOrNull(raw: String?): List<RemoteServer>? {
    // One bad record (renamed kind, torn write) is skipped, never fatal to its neighbours.
    val array = runCatching { JSONArray(raw ?: "") }.getOrNull() ?: return null
    return List(array.length(), array::getJSONObject).mapNotNull { obj ->
        val id = optString(obj, "id")
        val kindName = optString(obj, "kind")
        if (id == null || kindName == null) return@mapNotNull null
        val kind = RemoteKind.entries.firstOrNull { it.name == kindName }
        if (kind == null) return@mapNotNull null
        when (kind) {
            RemoteKind.SMB -> parseSmb(obj, id)
            RemoteKind.FTP -> parseFtp(obj, id)
            RemoteKind.KOMGA -> parseKomga(obj, id)
            RemoteKind.KAVITA -> parseKavita(obj, id)
        }
    }
}
