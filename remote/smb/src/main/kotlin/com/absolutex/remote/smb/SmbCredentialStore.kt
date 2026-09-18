package com.absolutex.remote.smb

/**
 * Password storage. Passwords travel as [CharArray] so callers can zero them after use;
 * a [String] would pin the secret in the string pool with no way to clear it.
 */
interface SmbCredentialStore {
    fun store(alias: String, password: CharArray)
    fun retrieve(alias: String): CharArray?
    fun clear(alias: String)
}

/** Test/placeholder store. Not for production — holds secrets in memory, still zeroed on clear. */
class InMemoryCredentialStore : SmbCredentialStore {
    private val guard = Any()
    private val secrets = HashMap<String, CharArray>()

    override fun store(alias: String, password: CharArray) {
        synchronized(guard) { secrets[alias] = password.copyOf() }
    }

    override fun retrieve(alias: String): CharArray? {
        synchronized(guard) { return secrets[alias]?.copyOf() }
    }

    override fun clear(alias: String) {
        synchronized(guard) {
            secrets.remove(alias)?.fill(Char.MIN_VALUE)
        }
    }
}
