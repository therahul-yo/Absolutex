package com.absolutex.remote.ftp

/**
 * Non-persistent vault: copies on the way in and out, zeroes on [clear]. Doubles as the
 * unit-test fake and as the memory-only store for sessions the user declined to persist.
 */
class InMemoryFtpCredentialStore : FtpCredentialStore {
    private val lock = Any()
    private val vault = mutableMapOf<String, CharArray>()

    override fun save(key: String, password: CharArray) {
        synchronized(lock) {
            vault[key]?.fill(CLEARED)
            vault[key] = password.copyOf()
        }
    }

    // Copies both ways isolate the vault: mutating a loaded array never rewrites the secret.
    override fun load(key: String): CharArray? = synchronized(lock) { vault[key]?.copyOf() }

    override fun clear(key: String) {
        synchronized(lock) { vault.remove(key)?.fill(CLEARED) }
    }

    companion object {
        private const val CLEARED = '\u0000'
    }
}
