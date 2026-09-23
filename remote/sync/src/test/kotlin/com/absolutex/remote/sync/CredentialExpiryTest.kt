package com.absolutex.remote.sync

import com.absolutex.remote.core.CredentialExpiredException
import com.absolutex.remote.core.TransportAuthException
import com.absolutex.remote.core.TransientExhaustedException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Re-auth seam: rotation carries its server id by type, everything else yields null —
 * so agent3's sign-in-again prompt cannot misfire on unreachable or auth failures.
 */
class CredentialExpiryTest {

    @Test fun `expiry carries its server id`() {
        assertEquals(
            "nas",
            credentialExpiredServerId(CredentialExpiredException("changed", serverId = "nas")),
        )
    }

    @Test fun `expiry without an id still signals re-auth`() {
        // FTP logins do not carry the id; the resolver falls back to the id it resolved
        // the transport for. Null here means "rotation, id unknown" — still re-auth,
        // never retry, never unreachable.
        assertNull(
            credentialExpiredServerId(CredentialExpiredException("changed")),
        )
    }

    @Test fun `other failures yield no server id`() {
        assertNull(credentialExpiredServerId(TransportAuthException("bad password")))
        assertNull(credentialExpiredServerId(TransientExhaustedException("used up", IOException("x"))))
        assertNull(credentialExpiredServerId(IOException("server down")))
    }
}
