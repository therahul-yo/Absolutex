package com.absolutex.remote.sync

/**
 * Sync secrets behind the Keystore-backed [CredentialStore]. Service ids embed the server id
 * and the secret kind, so two servers — or a key and a password on one server — can never
 * share a credential file (the store itself hashes service ids to SHA-256 hex, so even
 * adversarial ids cannot collide; see the alias test).
 */
class SyncSecrets(private val store: CredentialStore) {

    fun saveApiKey(serverId: String, key: CharArray) {
        store.save(apiKeyService(serverId), key)
    }

    fun loadApiKey(serverId: String): CharArray? = store.load(apiKeyService(serverId))

    fun savePassword(serverId: String, password: CharArray) {
        store.save(passwordService(serverId), password)
    }

    fun loadPassword(serverId: String): CharArray? = store.load(passwordService(serverId))

    fun saveSmbPassword(serverId: String, password: CharArray) {
        store.save(smbPasswordService(serverId), password)
    }

    fun loadSmbPassword(serverId: String): CharArray? = store.load(smbPasswordService(serverId))

    fun clearSmbPassword(serverId: String) {
        store.clear(smbPasswordService(serverId))
    }

    fun saveFtpPassword(serverId: String, password: CharArray) {
        store.save(ftpPasswordService(serverId), password)
    }

    fun loadFtpPassword(serverId: String): CharArray? = store.load(ftpPasswordService(serverId))

    fun clearFtpPassword(serverId: String) {
        store.clear(ftpPasswordService(serverId))
    }

    /** Clears all four secrets for [serverId] — call on server remove so nothing lingers. */
    fun clearServer(serverId: String) {
        store.clear(apiKeyService(serverId))
        store.clear(passwordService(serverId))
        store.clear(smbPasswordService(serverId))
        store.clear(ftpPasswordService(serverId))
    }

    companion object {
        fun apiKeyService(serverId: String): String = "sync/$serverId/api-key"

        fun passwordService(serverId: String): String = "sync/$serverId/password"

        fun smbPasswordService(serverId: String): String = "sync/$serverId/smb-password"

        fun ftpPasswordService(serverId: String): String = "sync/$serverId/ftp-password"
    }
}
