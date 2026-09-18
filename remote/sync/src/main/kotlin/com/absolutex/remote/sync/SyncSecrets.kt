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

    fun clear(serverId: String) {
        store.clear(apiKeyService(serverId))
        store.clear(passwordService(serverId))
    }

    companion object {
        fun apiKeyService(serverId: String): String = "sync/$serverId/api-key"

        fun passwordService(serverId: String): String = "sync/$serverId/password"
    }
}
