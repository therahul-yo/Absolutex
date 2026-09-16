package com.absolutex.remote.ftp

import java.io.IOException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class AesGcmBoxTest {

    private fun key() = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()

    @Test fun `seal and open round-trip`() {
        val master = key()
        val secret = "pässwörd-日本語".toCharArray()
        assertArrayEquals(secret, AesGcmBox.open(master, AesGcmBox.seal(master, secret)))
    }

    @Test fun `empty password round-trips`() {
        val master = key()
        val secret = CharArray(0)
        assertArrayEquals(secret, AesGcmBox.open(master, AesGcmBox.seal(master, secret)))
    }

    @Test fun `tampered ciphertext fails closed`() {
        val master = key()
        val sealed = AesGcmBox.seal(master, "s3cr3t".toCharArray())
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 0xFF).toByte()
        var thrown: IOException? = null
        try {
            AesGcmBox.open(master, sealed)
        } catch (expected: IOException) {
            thrown = expected
        }
        assertNotNull(thrown)
    }

    @Test fun `tampered iv fails closed`() {
        val master = key()
        val sealed = AesGcmBox.seal(master, "s3cr3t".toCharArray())
        sealed.iv[0] = (sealed.iv[0].toInt() xor 0xFF).toByte()
        var thrown: IOException? = null
        try {
            AesGcmBox.open(master, sealed)
        } catch (expected: IOException) {
            thrown = expected
        }
        assertNotNull(thrown)
    }

    @Test fun `wrong key fails closed`() {
        val sealed = AesGcmBox.seal(key(), "s3cr3t".toCharArray())
        var thrown: IOException? = null
        try {
            AesGcmBox.open(key(), sealed)
        } catch (expected: IOException) {
            thrown = expected
        }
        assertNotNull(thrown)
    }

    @Test fun `every seal uses a fresh iv`() {
        val master = key()
        val first = AesGcmBox.seal(master, "s3cr3t".toCharArray())
        val second = AesGcmBox.seal(master, "s3cr3t".toCharArray())
        assertFalse(first.iv.contentEquals(second.iv))
    }
}
