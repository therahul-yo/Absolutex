package com.absolutex.remote.cloud

import com.absolutex.remote.core.CredentialStore

/**
 * Where one cloud account's OAuth tokens live between runs.
 *
 * **Keyed by our own record id, never by the provider's account id.** An account id is unique
 * only within a provider, so two providers can hand back the same one; and adding the same
 * account twice — which people do, to reach two folders — would make the second overwrite the
 * first's tokens. The record id is ours, minted per row, and has neither problem.
 *
 * **Only the two secrets are stored.** When the access token expires is deliberately *not*
 * persisted: [CredentialStore] is for secrets, an expiry timestamp is not one, and a third key
 * would put a non-secret in the keystore to save a single round trip. The cost is named rather
 * than hidden — see [CloudSession], which treats a stored access token as of unknown validity
 * and refreshes when the server rejects it.
 */
class CloudTokenStore(private val credentials: CredentialStore) {

    /**
     * Persists what a token endpoint returned.
     *
     * A refresh response need not reissue a refresh token, and when it does not, the stored one
     * must survive: overwriting it with nothing would sign the user out at the next cold start,
     * having thrown away the only credential that could have rescued them.
     */
    fun save(recordId: String, tokens: TokenResponse) {
        credentials.save(accessTokenService(recordId), tokens.accessToken)
        tokens.refreshToken?.let { credentials.save(refreshTokenService(recordId), it) }
    }

    /** The stored access token, or null. Whether it is still valid is not knowable from here. */
    fun accessToken(recordId: String): CharArray? = credentials.load(accessTokenService(recordId))

    /** The stored refresh token, or null when this account has never completed a sign-in. */
    fun refreshToken(recordId: String): CharArray? = credentials.load(refreshTokenService(recordId))

    /** Forgets both tokens — sign-out, and the only honest response to a revoked grant. */
    fun clear(recordId: String) {
        credentials.clear(accessTokenService(recordId))
        credentials.clear(refreshTokenService(recordId))
    }

    companion object {
        /** `cloud/<record id>/access-token`. */
        fun accessTokenService(recordId: String): String = "$PREFIX/$recordId/access-token"

        /** `cloud/<record id>/refresh-token`. */
        fun refreshTokenService(recordId: String): String = "$PREFIX/$recordId/refresh-token"

        private const val PREFIX = "cloud"
    }
}
