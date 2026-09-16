package com.absolutex.remote.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12
private const val ALIAS_PREFIX = "absolutex-sync-"
private const val BLOB_SUFFIX = ".bin"

/**
 * CharArray-based credential storage. CharArray (not String) so callers can zero secrets after
 * use; implementations must copy on save/load so callers cannot mutate stored state.
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

/**
 * AES/GCM credential store backed by the platform AndroidKeyStore (platform only: instantiating
 * on the JVM throws, so this class is constructed on device and covered by live-server
 * validation, not unit tests). Encrypted blobs live as files under [privateDir]; key material
 * never leaves the KeyStore.
 */
class AndroidKeyStoreCredentialStore(private val privateDir: File) : CredentialStore {
    override fun save(service: String, secret: CharArray): Unit {
        val plain = secret.concatToString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(KEY_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(aliasFor(service)))
            // GCM generates a fresh random IV per encryption; prepend it for decrypt.
            writeFileAtomically(blobFile(service), cipher.iv + cipher.doFinal(plain))
        } catch (e: java.security.GeneralSecurityException) {
            throw IOException("credential encrypt failed", e)
        } finally {
            plain.fill(0)
        }
    }

    override fun load(service: String): CharArray? {
        val blob = blobFile(service).takeIf { it.exists() }?.readBytes() ?: return null
        // A truncated blob means a torn write; surface it rather than decrypting garbage.
        if (blob.size <= GCM_IV_BYTES) throw IOException("credential blob corrupt")
        return try {
            val cipher = Cipher.getInstance(KEY_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, key(aliasFor(service)), spec)
            val plain = cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
            String(plain, Charsets.UTF_8).toCharArray().also { plain.fill(0) }
        } catch (e: java.security.GeneralSecurityException) {
            throw IOException("credential decrypt failed", e)
        }
    }

    override fun clear(service: String): Unit {
        blobFile(service).delete()
    }

    private fun key(alias: String): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER)
        store.load(null)
        val entry = store.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey
        // First use: generate inside the KeyStore so raw key material never enters the process.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    // SHA-256 hex (not the old isLetterOrDigit filter) so distinct services never collide on the
    // Keystore alias or blob file name -- e.g. "komga:nas-1" and "komganas1" used to both strip
    // to "komganas1", so saving one server's secret silently overwrote the other's.
    private fun aliasFor(service: String): String = ALIAS_PREFIX + hashService(service)

    private fun blobFile(service: String): File = File(privateDir, "cred-" + hashService(service) + BLOB_SUFFIX)
}

/** Full SHA-256 hex digest (64 chars) of [service] -- collision-free, unlike stripping characters. */
internal fun hashService(service: String): String =
    MessageDigest.getInstance("SHA-256").digest(service.toByteArray(Charsets.UTF_8)).joinToString("") {
        "%02x".format(it)
    }

/**
 * Writes [bytes] to [target] via temp-file-then-rename in the same directory, so a crash or power
 * loss mid-write can never leave [target] holding a partial (corrupt) blob -- the rename is atomic
 * on the same filesystem, so readers always see either the old file or the fully-written new one.
 */
internal fun writeFileAtomically(target: File, bytes: ByteArray) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(target)) throw IOException("atomic rename failed: ${target.name}")
}
