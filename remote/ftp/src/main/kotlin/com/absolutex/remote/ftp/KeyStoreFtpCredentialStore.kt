package com.absolutex.remote.ftp

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [FtpCredentialStore] backed by an AndroidKeyStore AES/GCM key (hardware/TEE when available);
 * prefs hold only Base64 IV+ciphertext, never plaintext. Platform only, no new dependency.
 *
 * JVM unit tests cannot cover this (KeyStore `AndroidKeyStore` and `android.util.Base64` need a
 * device); the crypto itself is covered through [AesGcmBox], and this class stays a thin
 * key-management shell. Needs a device run before calling it done — the lane has no device.
 */
class KeyStoreFtpCredentialStore(
    private val prefs: SharedPreferences,
    private val alias: String = DEFAULT_ALIAS,
) : FtpCredentialStore {

    override fun save(key: String, password: CharArray) {
        val sealed = AesGcmBox.seal(masterKey(), password)
        val stored = Base64.encodeToString(sealed.iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(entryKey(key), stored).apply()
    }

    override fun load(key: String): CharArray? {
        val stored = prefs.getString(entryKey(key), null) ?: return null
        try {
            return openStored(stored)
        } catch (expected: IllegalArgumentException) {
            throw IOException("corrupt stored FTP credential", expected)
        }
    }

    override fun clear(key: String) {
        prefs.edit().remove(entryKey(key)).apply()
    }

    private fun openStored(stored: String): CharArray {
        val parts = stored.split(SEPARATOR)
        if (parts.size != PART_COUNT) throw IOException("corrupt stored FTP credential")
        val sealed = AesGcmBox.Sealed(decode(parts[IV_PART]), decode(parts[DATA_PART]))
        return AesGcmBox.open(masterKey(), sealed)
    }

    private fun decode(base64: String): ByteArray = Base64.decode(base64, Base64.NO_WRAP)

    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE)
        store.load(null)
        if (!store.containsAlias(alias)) generateKey()
        return (store.getKey(alias, null) as? SecretKey) ?: throw IOException("missing FTP key: $alias")
    }

    private fun generateKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    private fun entryKey(key: String): String = "$PREFIX$key"

    companion object {
        const val DEFAULT_ALIAS = "absolutex_ftp_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFIX = "ftp-credential/"
        private const val SEPARATOR = "."
        private const val PART_COUNT = 2
        private const val IV_PART = 0
        private const val DATA_PART = 1
    }
}
