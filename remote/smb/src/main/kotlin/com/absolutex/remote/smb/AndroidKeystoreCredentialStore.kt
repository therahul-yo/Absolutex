package com.absolutex.remote.smb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production store: AES-256-GCM in the platform AndroidKeyStore, ciphertext in a private file.
 * The key never leaves hardware-backed storage where the device offers it, and the password
 * never touches disk in plaintext. Untested on the JVM (needs the keystore daemon) — covered
 * by [InMemoryCredentialStore] tests plus manual device verification, stated in the lane PR.
 */
class AndroidKeystoreCredentialStore(private val storageDir: File) : SmbCredentialStore {

    override fun store(alias: String, password: CharArray) {
        val plain = password.concatToString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plain)
            val iv = cipher.iv
            val out = ByteArrayOutputStream(INT_BYTES + iv.size + encrypted.size)
            out.write(ByteBuffer.allocate(INT_BYTES).putInt(iv.size).array())
            out.write(iv)
            out.write(encrypted)
            fileFor(alias).writeBytes(out.toByteArray())
        } finally {
            plain.fill(0)
        }
    }

    override fun retrieve(alias: String): CharArray? {
        val file = fileFor(alias)
        if (!file.exists()) return null
        val blob = file.readBytes()
        if (blob.size <= INT_BYTES) return null
        val ivSize = ByteBuffer.wrap(blob, 0, INT_BYTES).int
        if (ivSize <= 0 || blob.size <= INT_BYTES + ivSize) return null
        val iv = blob.copyOfRange(INT_BYTES, INT_BYTES + ivSize)
        val cipherText = blob.copyOfRange(INT_BYTES + ivSize, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // GCM tag failure throws AEADBadTagException on tamper — surfaced, never silent wrong bytes.
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(cipherText)
        try {
            return String(plain, Charsets.UTF_8).toCharArray()
        } finally {
            plain.fill(0)
        }
    }

    override fun clear(alias: String) {
        fileFor(alias).delete()
    }

    private fun fileFor(alias: String): File {
        // Alias is caller-chosen (e.g. location id), not a path — encode separators, never
        // traverse. Percent-encoding is injective where stripping was not: "a/b" and "a\b"
        // both became "a_b" and shared one credential file, sending one server's password
        // to another host.
        return File(storageDir, "${sanitiseAlias(alias)}$SUFFIX")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE)
        store.load(null)
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "absolutex_smb_creds"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val INT_BYTES = 4
        private const val SUFFIX = ".smbcred"

        /**
         * Reversible separator encoding for credential filenames. Percent first, or the
         * escapes themselves collide ("a%2F" must not equal "a/").
         */
        internal fun sanitiseAlias(alias: String): String =
            alias.replace("%", "%25").replace("/", "%2F").replace("\\", "%5C")
    }
}
