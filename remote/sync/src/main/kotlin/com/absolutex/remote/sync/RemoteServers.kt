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

private val Context.remoteServersStore by preferencesDataStore(name = "remote_servers")

private const val MIN_PORT = 1
private const val MAX_PORT = 65535

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

/**
 * One persisted remote server for the future servers screen. Secrets never live here — only
 * non-secret identity fields; passwords and API keys stay in their credential stores.
 */
sealed interface RemoteServer {
    val id: String
    val kind: RemoteKind
}

/** One SMB share plus the path inside it to read from. */
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

/**
 * Validates an SMB record for storage. Null means valid. Mirrors SmbLocation's require-rules:
 * blank host/share/path/username, a port outside 1..65535, and any ".." path segment.
 */
fun validateSmb(server: SmbServer): String? {
    if (server.host.isBlank()) return "SMB host must not be blank"
    if (server.share.isBlank()) return "SMB share must not be blank"
    if (server.path.isBlank()) return "SMB path must not be blank"
    if (server.username.isBlank()) return "SMB username must not be blank"
    if (server.port !in MIN_PORT..MAX_PORT) return "SMB port out of range: ${server.port}"
    if (hasParentEscape(server.path)) return "SMB path must not escape its root: ${server.path}"
    return null
}

/**
 * Validates an FTP record for storage. Null means valid. Mirrors FtpLocation's require-rules
 * (blank host/path/username, port range, no ".." path segment).
 *
 * The useTls-vs-allowCleartext combination is deliberately NOT validated here: plain FTP with
 * allowCleartext=false stays storable and fails closed at connect time instead, so a record
 * restored from a backup is never refused by the store over a policy call the store cannot make.
 */
fun validateFtp(server: FtpServer): String? {
    if (server.host.isBlank()) return "FTP host must not be blank"
    if (server.port !in MIN_PORT..MAX_PORT) return "FTP port out of range: ${server.port}"
    if (server.username.isBlank()) return "FTP username must not be blank"
    if (server.path.isBlank()) return "FTP path must not be blank"
    if (hasParentEscape(server.path)) return "FTP path must not escape its root: ${server.path}"
    return null
}

private fun hasParentEscape(path: String): Boolean =
    path.replace('\\', '/').split('/').any { it == ".." }

/** Trims whitespace and trailing slashes; a bare root ("/") is kept, never emptied. */
private fun normalisePath(path: String): String {
    val trimmed = path.trim()
    val stripped = trimmed.trimEnd('/', '\\')
    if (stripped.isEmpty()) return trimmed
    return stripped
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
 * Persisted server list for every remote kind (DataStore, one JSON document). No UI in this
 * milestone — the future servers screen manages these records; keyed by caller-assigned ids.
 *
 * Seam: M5's [SyncServers] stays the sync runtime read-model; a later milestone migrates sync
 * onto this list, at which point SyncServers becomes a view over these records.
 */
class RemoteServers(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.remoteServersStore)

    val servers: Flow<List<RemoteServer>> = store.data.map { prefs ->
        val raw = prefs[SERVERS_KEY] ?: return@map emptyList()
        parseServers(raw)
    }

    suspend fun current(): List<RemoteServer> = servers.first()

    suspend fun save(server: RemoteServer) {
        val error = validateOf(server)
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
        JSONArray(servers.map(::serialiseOne)).toString()

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

    private fun parseServers(raw: String?): List<RemoteServer> {
        // One bad record (renamed kind, torn write) is skipped, never fatal to its neighbours.
        val array = runCatching { JSONArray(raw ?: "") }.getOrNull() ?: return emptyList()
        return List(array.length(), array::getJSONObject).mapNotNull(::parseOne)
    }

    private fun parseOne(obj: JSONObject): RemoteServer? {
        val id = optString(obj, "id")
        val kindName = optString(obj, "kind")
        if (id == null || kindName == null) return null
        val kind = RemoteKind.entries.firstOrNull { it.name == kindName }
        if (kind == null) return null
        return when (kind) {
            RemoteKind.SMB -> parseSmb(obj, id)
            RemoteKind.FTP -> parseFtp(obj, id)
            RemoteKind.KOMGA -> parseKomga(obj, id)
            RemoteKind.KAVITA -> parseKavita(obj, id)
        }
    }

    companion object {
        private val SERVERS_KEY = stringPreferencesKey("servers")
    }
}
