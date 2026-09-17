package com.absolutex.remote.smb

import com.hierynomus.mssmb2.SMB2Dialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the signing/encryption posture: reverting the defaults (or flipping one line in
 * [SmbLocation]) must fail here, not on a NAS. Built against smbj-0.15.0, verified by javap.
 */
class SmbConfigTest {

    @Test fun `default requires signing and encryption on smb3 only`() {
        val config = SmbConfigFactory.build(allowUnsigned = false)
        assertEquals(
            setOf(SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_1_1),
            config.supportedDialects,
        )
        assertTrue(config.isSigningRequired)
        assertTrue(config.isEncryptData)
    }

    @Test fun `unsigned opt-out downgrades explicitly`() {
        val config = SmbConfigFactory.build(allowUnsigned = true)
        assertTrue(config.supportedDialects.contains(SMB2Dialect.SMB_2_1))
        assertFalse(config.isSigningRequired)
        assertFalse(config.isEncryptData)
    }

    @Test fun `timeouts bound a stalled server`() {
        val config = SmbConfigFactory.build(allowUnsigned = false)
        assertTrue(config.soTimeout > 0)
        assertTrue(config.readTimeout > 0)
        assertTrue(config.writeTimeout > 0)
        assertTrue(config.transactTimeout > 0)
    }
}
