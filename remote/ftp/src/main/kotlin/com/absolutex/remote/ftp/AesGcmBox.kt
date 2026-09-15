package com.absolutex.remote.ftp

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM seal for FTP passwords. Pure `javax.crypto`, so JVM unit tests cover the crypto while
 * [KeyStoreFtpCredentialStore] stays a thin AndroidKeyStore-plus-prefs shell until a device run.
 * GCM authenticates IV and ciphertext together: any tampering fails closed.
 */
internal object AesGcmBox {

    /** IV plus ciphertext of one sealed password. Compared by identity; round-trip to compare. */
    class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    @Throws(IOException::class)
    fun seal(key: SecretKey, password: CharArray): Sealed {
        val plain = password.concatToString().toByteArray(Charsets.UTF_8)
        // Fresh IV per seal: reusing a GCM nonce under one key destroys confidentiality.
        val iv = ByteArray(IV_BYTES)
        SecureRandom().nextBytes(iv)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return Sealed(iv, cipher.doFinal(plain))
        } finally {
            // Zeroes the transient UTF-8 bytes; the caller's CharArray is theirs to clear.
            plain.fill(CLEARED)
        }
    }

    @Throws(IOException::class)
    fun open(key: SecretKey, sealed: Sealed): CharArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
            val plain = cipher.doFinal(sealed.ciphertext)
            try {
                return String(plain, Charsets.UTF_8).toCharArray()
            } finally {
                plain.fill(CLEARED)
            }
        } catch (expected: GeneralSecurityException) {
            throw IOException("cannot open sealed FTP password", expected)
        }
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    private const val CLEARED: Byte = 0
}
