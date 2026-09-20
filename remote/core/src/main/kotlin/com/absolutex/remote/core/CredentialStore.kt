package com.absolutex.remote.core

/**
 * CharArray-based credential storage. CharArray (not String) so callers can zero secrets after
 * use; implementations must copy on save/load so callers cannot mutate stored state.
 *
 * Lives here rather than beside its Android implementation for the same reason [HttpCall] does:
 * the interface is pure JVM, and keeping it in an Android library meant a plain-JVM module could
 * not reach it without depending on the whole sync engine. `AndroidKeyStoreCredentialStore` —
 * the one part that genuinely needs the platform — stays in `:remote:sync`.
 */
interface CredentialStore {
    fun save(service: String, secret: CharArray)
    fun load(service: String): CharArray?
    fun clear(service: String)
}

/** In-memory fake: copies on save/load, zeroes on clear. Drives JVM tests (no KeyStore there). */
class InMemoryCredentialStore : CredentialStore {
    private val secrets = mutableMapOf<String, CharArray>()

    override fun save(service: String, secret: CharArray): Unit {
        secrets[service] = secret.copyOf()
    }

    override fun load(service: String): CharArray? = secrets[service]?.copyOf()

    override fun clear(service: String): Unit {
        secrets.remove(service)?.fill('\u0000')
    }
}
