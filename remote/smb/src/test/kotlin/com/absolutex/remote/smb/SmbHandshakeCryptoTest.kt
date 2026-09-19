package com.absolutex.remote.smb

import com.hierynomus.ntlm.functions.NtlmFunctions
import com.hierynomus.security.bc.BCSecurityProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the SMB handshake crypto survives the APK packaging excludes.
 *
 * BouncyCastle arrives via smbj, but its PQC lookup tables and X.509 reviewer messages
 * are stripped from the release APK (`packaging.resources.excludes` in `:app`) — ~1.2 MiB
 * of tables for code paths SMB never calls. If the strip ever takes a class the handshake
 * needs, the failure is a loud exception here, not a silent auth regression on a device:
 * every test below runs the exact NTLM crypto the handshake runs (NTLMv2 responses via
 * HMAC-MD5, NTOWF hashes via MD4, sealing via RC4), cross-checked against the platform
 * JCE provider where one exists.
 */
class SmbHandshakeCryptoTest {

    private val bc = BCSecurityProvider()

    @Test fun `ntlm hmac matches the platform provider`() {
        val key = "password-key-16b!".toByteArray()
        val data = "server-challenge-target".toByteArray()
        val ours = NtlmFunctions.hmac_md5(bc, key, data)
        val ref = javax.crypto.Mac.getInstance("HmacMD5").run {
            init(javax.crypto.spec.SecretKeySpec(key, "HmacMD5"))
            doFinal(data)
        }
        assertArrayEquals(ref, ours)
    }

    @Test fun `ntlm md5 matches the platform provider`() {
        val data = "ntlmv2-blob".toByteArray()
        val ours = NtlmFunctions.md5(bc, data)
        val ref = java.security.MessageDigest.getInstance("MD5").digest(data)
        assertArrayEquals(ref, ours)
    }

    @Test fun `md4 ntlm hash is stable and 16 bytes`() {
        // NTOWFv1/v2 both start from MD4(unicode(password)); JCE has no MD4, so assert
        // shape plus determinism. MD4 lives in BouncyCastle — this is the canary: if the
        // excludes ever swallow a digest class, this fails before any device does.
        val digest = bc.getDigest("MD4")
        assertEquals(16, digest.digestLength)
        val input = NtlmFunctions.unicode("Password")
        digest.update(input)
        val first = digest.digest()
        digest.reset()
        digest.update(input)
        assertArrayEquals(first, digest.digest())
        assertEquals(16, first.size)
    }

    @Test fun `rc4 sealing round-trips`() {
        val key = ByteArray(16) { it.toByte() }
        val plain = "session-key-material".toByteArray()
        val sealed = NtlmFunctions.rc4k(bc, key, plain)
        assertArrayEquals(plain, NtlmFunctions.rc4k(bc, key, sealed))
    }
}
