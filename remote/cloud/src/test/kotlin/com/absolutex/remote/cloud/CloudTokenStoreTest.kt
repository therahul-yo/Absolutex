package com.absolutex.remote.cloud

import com.absolutex.remote.core.InMemoryCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Where tokens live between runs, and under exactly which keys. */
class CloudTokenStoreTest {

    private val credentials = InMemoryCredentialStore()
    private val store = CloudTokenStore(credentials)

    private fun tokens(access: String, refresh: String?) =
        TokenResponse(access.toCharArray(), refresh?.toCharArray(), 3600L)

    @Test fun `the keys are cloud slash record id slash token name`() {
        // Pinned as literals rather than built from the same helper the code uses: a test that
        // asked the production code what the key was would agree with any key it was changed to,
        // and these strings are storage — changing one silently signs every account out.
        assertEquals("cloud/rec-1/access-token", CloudTokenStore.accessTokenService("rec-1"))
        assertEquals("cloud/rec-1/refresh-token", CloudTokenStore.refreshTokenService("rec-1"))
    }

    @Test fun `two accounts of the same provider do not share storage`() {
        // The reason the key is our record id and not the provider's account id: the same
        // account added twice is a real thing people do, to reach two different folders.
        store.save("rec-1", tokens("access-1", "refresh-1"))
        store.save("rec-2", tokens("access-2", "refresh-2"))
        assertEquals("access-1", store.accessToken("rec-1")?.concatToString())
        assertEquals("access-2", store.accessToken("rec-2")?.concatToString())
    }

    @Test fun `saving round-trips both tokens`() {
        store.save("rec-1", tokens("access-1", "refresh-1"))
        assertEquals("access-1", store.accessToken("rec-1")?.concatToString())
        assertEquals("refresh-1", store.refreshToken("rec-1")?.concatToString())
    }

    @Test fun `a response with no refresh token keeps the one already stored`() {
        // RFC 6749 lets a refresh response omit a new refresh token. Overwriting with nothing
        // would discard the only credential that can recover the account at the next cold start.
        store.save("rec-1", tokens("access-1", "refresh-1"))
        store.save("rec-1", tokens("access-2", null))
        assertEquals("the access token must be replaced", "access-2", store.accessToken("rec-1")?.concatToString())
        assertEquals("the refresh token must survive", "refresh-1", store.refreshToken("rec-1")?.concatToString())
    }

    @Test fun `clearing forgets both tokens, not just one`() {
        store.save("rec-1", tokens("access-1", "refresh-1"))
        store.clear("rec-1")
        assertNull(store.accessToken("rec-1"))
        assertNull("a surviving refresh token would silently resurrect the account", store.refreshToken("rec-1"))
    }

    @Test fun `clearing one account leaves the others signed in`() {
        store.save("rec-1", tokens("access-1", "refresh-1"))
        store.save("rec-2", tokens("access-2", "refresh-2"))
        store.clear("rec-1")
        assertNull(store.accessToken("rec-1"))
        assertNotNull(store.accessToken("rec-2"))
    }

    @Test fun `an account that never signed in has nothing stored`() {
        assertNull(store.accessToken("unknown"))
        assertNull(store.refreshToken("unknown"))
    }

    @Test fun `a loaded token is a copy, so zeroing it does not empty the store`() {
        store.save("rec-1", tokens("access-1", "refresh-1"))
        store.accessToken("rec-1")?.fill('\u0000')
        assertEquals("access-1", store.accessToken("rec-1")?.concatToString())
    }
}
