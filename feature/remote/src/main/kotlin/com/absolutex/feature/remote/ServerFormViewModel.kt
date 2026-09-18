package com.absolutex.feature.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.remote.ftp.FtpLocation
import com.absolutex.remote.smb.SmbLocation
import com.absolutex.remote.sync.ConnectionResult
import com.absolutex.remote.sync.FtpServer
import com.absolutex.remote.sync.KavitaConnectionProbe
import com.absolutex.remote.sync.KavitaServer
import com.absolutex.remote.sync.KomgaAuth
import com.absolutex.remote.sync.KomgaConnectionProbe
import com.absolutex.remote.sync.KomgaServer
import com.absolutex.remote.sync.RemoteKind
import com.absolutex.remote.sync.RemoteServer
import com.absolutex.remote.sync.RemoteServers
import com.absolutex.remote.sync.SmbServer
import com.absolutex.remote.sync.SyncSecrets
import com.absolutex.remote.sync.validateServerUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** Add-and-edit form state. `serverId` null means a new server. */
data class ServerForm(
    val serverId: String? = null,
    val kind: RemoteKind = RemoteKind.SMB,
    val host: String = "",
    val share: String = "",
    val path: String = "",
    val port: String = "",
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    val useApiKey: Boolean = false,
    val baseUrl: String = "",
    val useTls: Boolean = true,
    val allowCleartext: Boolean = false,
    val allowUnsigned: Boolean = false,
)

/** Form status: field errors, a pending/finished live test, a validation failure, or saved. */
data class ServerFormStatus(
    val invalidFields: Set<String> = emptySet(),
    val testing: Boolean = false,
    val testResult: ConnectionResult? = null,
    val saveBlocked: Boolean = false,
    val saved: Boolean = false,
)

/**
 * Add-and-edit form for one server. Validation gates both the live test and the save;
 * errors are field keys (the UI maps them to resources — the validators' English messages
 * never reach the screen). Secrets resolve as typed text first, stored secret second, so an
 * untouched password field on edit tests and saves against what is already stored; a blank
 * password on save keeps the stored secret, and a new server requires one.
 */
@HiltViewModel
class ServerFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val servers: RemoteServers,
    private val secrets: SyncSecrets,
    private val komgaProbe: KomgaConnectionProbe,
    private val kavitaProbe: KavitaConnectionProbe,
    private val ftpProbe: FtpConnectionProbe,
    private val smbProbe: SmbConnectionTester,
) : ViewModel() {

    private val _form = MutableStateFlow(ServerForm())

    /** Form content; the screen writes through [update]. */
    val form: StateFlow<ServerForm> = _form.asStateFlow()

    private val _status = MutableStateFlow(ServerFormStatus())

    /** Validation, test and save status for the screen. */
    val status: StateFlow<ServerFormStatus> = _status.asStateFlow()

    /**
     * The record's kind when editing started. A kind change would carry the prefilled
     * secret into the new kind's slot (and the next test would send it to that server),
     * so changing kind means deleting and adding — the save and the test both refuse it.
     * Null for new servers and until prefill lands, where there is nothing to protect.
     */
    private var originalKind: RemoteKind? = null

    init {
        savedStateHandle.get<String>("serverId")?.let { id ->
            viewModelScope.launch {
                servers.current().firstOrNull { it.id == id }?.let { prefill(id, it) }
            }
        }
    }

    /** Single writer for every field, keeping the public surface to update/test/save. */
    fun update(form: ServerForm) {
        _form.value = form
        _status.value = _status.value.copy(saveBlocked = false)
    }

    /** Live test on the current values; gated on validation like the save. */
    fun testConnection() {
        val form = _form.value
        val invalid = invalidFields(form).toMutableSet()
        if (kindChanged(form)) {
            invalid += FIELD_KIND
        }
        if (invalid.isNotEmpty()) {
            _status.value = _status.value.copy(invalidFields = invalid, saveBlocked = true)
            return
        }
        val secret = takeSecret(form)
        if (secret == null) {
            _status.value = _status.value.copy(
                invalidFields = invalid + secretField(form),
                saveBlocked = true,
            )
            return
        }
        _status.value = _status.value.copy(invalidFields = emptySet(), testing = true, testResult = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = probe(form, secret)
                _status.value = _status.value.copy(testing = false, testResult = result)
            } finally {
                secret.fill(Char.MIN_VALUE)
            }
        }
    }

    fun save() {
        val form = _form.value
        val invalid = invalidFields(form).toMutableSet()
        if (kindChanged(form)) {
            invalid += FIELD_KIND
        }
        // A new server with no secret anywhere would save a record that can never connect.
        if (form.serverId == null && secretText(form).isEmpty() && !hasSecret(form)) {
            invalid += secretField(form)
        }
        if (invalid.isNotEmpty()) {
            _status.value = _status.value.copy(invalidFields = invalid, saveBlocked = true)
            return
        }
        viewModelScope.launch {
            val id = form.serverId ?: UUID.randomUUID().toString()
            // runCatching, not try/catch: a store failure blocks the save without crashing,
            // and the failure stays inspectable instead of swallowed.
            val done = runCatching {
                servers.save(buildRecord(form, id))
                val text = secretText(form)
                if (text.isNotEmpty()) {
                    val secret = text.toCharArray()
                    try {
                        when (form.kind) {
                            RemoteKind.SMB -> secrets.saveSmbPassword(id, secret)
                            RemoteKind.FTP -> secrets.saveFtpPassword(id, secret)
                            RemoteKind.KOMGA, RemoteKind.KAVITA -> {
                                if (form.useApiKey) secrets.saveApiKey(id, secret)
                                else secrets.savePassword(id, secret)
                            }
                        }
                    } finally {
                        secret.fill(Char.MIN_VALUE)
                    }
                }
            }
            _status.value = _status.value.copy(saved = done.isSuccess, saveBlocked = done.isFailure)
        }
    }

    /** Presence check only: the loaded copy is zeroed at once, never kept. */
    private fun hasSecret(form: ServerForm): Boolean {
        val stored = takeStoredSecret(form)
        stored?.fill(Char.MIN_VALUE)
        return stored != null
    }

    private fun prefill(id: String, server: RemoteServer) {
        val base = when (server) {
            is SmbServer -> ServerForm(
                serverId = id, kind = RemoteKind.SMB, host = server.host, share = server.share,
                path = server.path, port = server.port.toString(), username = server.username,
                allowUnsigned = server.allowUnsigned,
            )
            is FtpServer -> ServerForm(
                serverId = id, kind = RemoteKind.FTP, host = server.host, path = server.path,
                port = server.port.toString(), username = server.username, useTls = server.useTls,
                allowCleartext = server.allowCleartext,
            )
            is KomgaServer -> ServerForm(
                serverId = id, kind = RemoteKind.KOMGA, baseUrl = server.baseUrl,
                username = server.username.orEmpty(), useApiKey = server.usesApiKey,
                allowCleartext = server.allowCleartext,
            )
            is KavitaServer -> ServerForm(
                serverId = id, kind = RemoteKind.KAVITA, baseUrl = server.baseUrl,
                username = server.username.orEmpty(), useApiKey = server.usesApiKey,
                allowCleartext = server.allowCleartext,
            )
        }
        // Text fields need Strings: the stored copy becomes text here and is zeroed at once.
        // It lives on as an immutable String while editing — the price of an editable field.
        val stored = takeStoredSecret(base)
        val text = stored?.concatToString().orEmpty()
        stored?.fill(Char.MIN_VALUE)
        val apiKeyKind = (server is KomgaServer && server.usesApiKey) ||
            (server is KavitaServer && server.usesApiKey)
        originalKind = server.kind
        _form.value = if (apiKeyKind) base.copy(apiKey = text) else base.copy(password = text)
    }

    private suspend fun probe(form: ServerForm, secret: CharArray): ConnectionResult? =
        withContext(Dispatchers.IO) {
            when (form.kind) {
                RemoteKind.KOMGA -> {
                    val auth = if (form.useApiKey) {
                        KomgaAuth.ApiKey(secret.copyOf())
                    } else {
                        KomgaAuth.Basic(form.username, secret.copyOf())
                    }
                    try {
                        komgaProbe.test(form.baseUrl, auth)
                    } finally {
                        (auth as? KomgaAuth.ApiKey)?.key?.fill(Char.MIN_VALUE)
                        (auth as? KomgaAuth.Basic)?.password?.fill(Char.MIN_VALUE)
                    }
                }
                RemoteKind.KAVITA -> {
                    if (form.useApiKey) {
                        kavitaProbe.test(form.baseUrl, KavitaConnectionProbe.Credentials.ApiKey(secret.copyOf()))
                    } else {
                        kavitaProbe.test(
                            form.baseUrl,
                            KavitaConnectionProbe.Credentials.Login(form.username, secret.copyOf()),
                        )
                    }
                }
                RemoteKind.FTP -> ftpProbe.test(
                    FtpLocation(
                        host = form.host.trim(),
                        port = form.port.toInt(),
                        username = form.username.trim(),
                        path = form.path.trim(),
                        useTls = form.useTls,
                    ),
                    secret.copyOf(),
                )
                RemoteKind.SMB -> {
                    val location = SmbLocation(
                        host = form.host.trim(),
                        share = form.share.trim(),
                        path = form.path.trim(),
                        port = form.port.toInt(),
                        username = form.username.trim(),
                        allowUnsigned = form.allowUnsigned,
                    )
                    smbProbe.test(secret.copyOf()) { SmbjConnector(location).connect(it) }
                }
            }
        }

    private fun buildRecord(form: ServerForm, id: String): RemoteServer = when (form.kind) {
        RemoteKind.SMB -> SmbServer(
            id = id, host = form.host.trim(), share = form.share.trim(), path = form.path.trim(),
            port = form.port.toInt(), username = form.username.trim(), allowUnsigned = form.allowUnsigned,
        )
        RemoteKind.FTP -> FtpServer(
            id = id, host = form.host.trim(), port = form.port.toInt(), path = form.path.trim(),
            username = form.username.trim(), useTls = form.useTls, allowCleartext = form.allowCleartext,
        )
        RemoteKind.KOMGA -> KomgaServer(
            id = id, baseUrl = form.baseUrl.trim(), allowCleartext = form.allowCleartext,
            username = form.username.trim().ifEmpty { null }, usesApiKey = form.useApiKey,
        )
        RemoteKind.KAVITA -> KavitaServer(
            id = id, baseUrl = form.baseUrl.trim(), allowCleartext = form.allowCleartext,
            username = form.username.trim().ifEmpty { null }, usesApiKey = form.useApiKey,
        )
    }

    private fun invalidFields(form: ServerForm): Set<String> = when (form.kind) {
        RemoteKind.SMB -> smbInvalidFields(form)
        RemoteKind.FTP -> ftpInvalidFields(form)
        RemoteKind.KOMGA, RemoteKind.KAVITA -> syncInvalidFields(form)
    }

    /** True when editing changed the record's kind — the secret must not follow it. */
    private fun kindChanged(form: ServerForm): Boolean {
        val original = originalKind
        return form.serverId != null && original != null && form.kind != original
    }

    /** Fresh secret copy (typed text first, storage second) or null when neither has one. */
    private fun takeSecret(form: ServerForm): CharArray? {
        val text = secretText(form)
        if (text.isNotEmpty()) return text.toCharArray()
        return takeStoredSecret(form)
    }

    private fun takeStoredSecret(form: ServerForm): CharArray? {
        val id = form.serverId ?: return null
        return when (form.kind) {
            RemoteKind.SMB -> secrets.loadSmbPassword(id)
            RemoteKind.FTP -> secrets.loadFtpPassword(id)
            RemoteKind.KOMGA, RemoteKind.KAVITA -> {
                if (form.useApiKey) secrets.loadApiKey(id) else secrets.loadPassword(id)
            }
        }
    }

    companion object {
        const val FIELD_HOST = "host"
        const val FIELD_SHARE = "share"
        const val FIELD_PATH = "path"
        const val FIELD_PORT = "port"
        const val FIELD_USERNAME = "username"
        const val FIELD_PASSWORD = "password"
        const val FIELD_API_KEY = "api_key"
        const val FIELD_BASE_URL = "base_url"
        const val FIELD_KIND = "kind"
    }
}

/** Typed secret text, or blank when the untouched field must fall back to storage. */
private fun secretText(form: ServerForm): String =
    if (form.useApiKey && (form.kind == RemoteKind.KOMGA || form.kind == RemoteKind.KAVITA)) {
        form.apiKey
    } else {
        form.password
    }

private fun secretField(form: ServerForm): String =
    if (form.useApiKey && (form.kind == RemoteKind.KOMGA || form.kind == RemoteKind.KAVITA)) {
        ServerFormViewModel.FIELD_API_KEY
    } else {
        ServerFormViewModel.FIELD_PASSWORD
    }

private fun smbInvalidFields(form: ServerForm): Set<String> {
    val invalid = mutableSetOf<String>()
    if (form.host.isBlank()) invalid += ServerFormViewModel.FIELD_HOST
    if (form.share.isBlank()) invalid += ServerFormViewModel.FIELD_SHARE
    if (form.path.isBlank() || hasParentEscape(form.path)) invalid += ServerFormViewModel.FIELD_PATH
    if (form.port.toIntOrNull() !in MIN_PORT..MAX_PORT) invalid += ServerFormViewModel.FIELD_PORT
    if (form.username.isBlank()) invalid += ServerFormViewModel.FIELD_USERNAME
    return invalid
}

private fun ftpInvalidFields(form: ServerForm): Set<String> {
    val invalid = mutableSetOf<String>()
    if (form.host.isBlank()) invalid += ServerFormViewModel.FIELD_HOST
    if (form.path.isBlank() || hasParentEscape(form.path)) invalid += ServerFormViewModel.FIELD_PATH
    if (form.port.toIntOrNull() !in MIN_PORT..MAX_PORT) invalid += ServerFormViewModel.FIELD_PORT
    if (form.username.isBlank()) invalid += ServerFormViewModel.FIELD_USERNAME
    return invalid
}

private fun syncInvalidFields(form: ServerForm): Set<String> {
    val invalid = mutableSetOf<String>()
    if (validateServerUrl(form.baseUrl, form.allowCleartext) != null) {
        invalid += ServerFormViewModel.FIELD_BASE_URL
    }
    if (!form.useApiKey && form.username.isBlank()) invalid += ServerFormViewModel.FIELD_USERNAME
    return invalid
}

private fun hasParentEscape(path: String): Boolean =
    path.replace('\\', '/').split('/').any { it == ".." }

private const val MIN_PORT = 1
private const val MAX_PORT = 65535
