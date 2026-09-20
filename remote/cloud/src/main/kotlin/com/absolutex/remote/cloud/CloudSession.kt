package com.absolutex.remote.cloud

import com.absolutex.remote.core.AUTHORIZATION

/**
 * One signed-in cloud account's live token, refreshed when the provider stops accepting it.
 *
 * **Why a 401 drives this rather than a stored expiry.** [CloudTokenStore] keeps the two
 * secrets and nothing else, so a stored access token is of unknown age. That is not a
 * weakness: an expiry timestamp only ever predicts rejection, while a token can also be
 * revoked, have its consent withdrawn, or be invalidated by a password change long before it
 * expires — all of which look identical from here and none of which a timestamp catches. The
 * server's refusal is the only authority worth trusting. The price is one wasted round trip on
 * a cold start with a stale token, which is a fair trade for never presenting a dead credential
 * because the clock said it was fine.
 *
 * **Refreshing is single-flight, and that is a correctness requirement, not a nicety.** Reads
 * fan out — [HttpRangeTransport.readAt] is explicitly concurrent — so an expired token means
 * several requests fail at once. Most providers **rotate** the refresh token, invalidating the
 * old one the moment it is used, so two concurrent refreshes mean one wins and the other
 * spends a token that is already dead, breaking the chain and signing the user out for good.
 * A generation counter, read before the call and re-checked under the lock, means the loser of
 * the race waits for the winner's token instead of burning its own.
 *
 * **On secrets.** Tokens live as CharArrays and every plaintext copy made here is zeroed in a
 * `finally`. The one copy that cannot be is the header value: `HttpCall` takes its headers as
 * `Map<String, String>`, so the access token becomes immutable text for as long as the JVM
 * keeps it. That is the seam's shape, the same limitation named in [CloudTokenEndpoint], and
 * it is written down here rather than worked around.
 *
 * **Threading.** [withAccessToken] blocks — it may make a token request — so callers run it off
 * the main thread, exactly as they already must for the read it wraps.
 */
class CloudSession(
    private val recordId: String,
    private val store: CloudTokenStore,
    private val endpoint: CloudTokenEndpoint,
) {

    private val refreshLock = Any()

    /** Bumped on every successful refresh, so a racing caller can tell it lost rather than retry. */
    @Volatile
    private var generation: Long = 0

    /**
     * Runs [call] with an Authorization header, refreshing once if the provider rejects it.
     *
     * Exactly one retry: a token minted seconds ago and still refused means the grant itself is
     * gone, and asking again would only spend rate limit on a question already answered.
     */
    fun <T> withAccessToken(call: (headers: Map<String, String>) -> T): T {
        val before = generation
        return try {
            call(authorizationHeaders())
        } catch (rejected: ReauthRequiredException) {
            // Also the cold-start path: no stored access token throws the same exception, so
            // "never signed in on this device" and "token refused" take one route, not two.
            refreshUnlessOvertaken(before, rejected)
            call(authorizationHeaders())
        }
    }

    /** Forgets both tokens. The honest response to a sign-out, and to a grant that is gone. */
    fun signOut() {
        store.clear(recordId)
    }

    private fun authorizationHeaders(): Map<String, String> {
        val token = store.accessToken(recordId)
            ?: throw ReauthRequiredException("sign-in required: no access token stored")
        return try {
            mapOf(AUTHORIZATION to "$BEARER_PREFIX${token.concatToString()}")
        } finally {
            token.fill('\u0000')
        }
    }

    /**
     * Refreshes, unless another caller already did so since [before] was read — in which case
     * there is a fresh token waiting and spending a rotated refresh token would destroy it.
     */
    private fun refreshUnlessOvertaken(before: Long, rejected: ReauthRequiredException) {
        synchronized(refreshLock) {
            if (generation != before) return
            val refresh = store.refreshToken(recordId) ?: throw rejected
            try {
                // Deliberately inside the lock: this is the whole point of the single flight,
                // and it is bounded by the endpoint's own rate-limit retry budget.
                endpoint.refresh(refresh).use { store.save(recordId, it) }
            } catch (gone: ReauthRequiredException) {
                // The refresh token itself was refused, so nothing stored can recover this
                // account. Keeping it would mean re-presenting a dead credential for ever.
                store.clear(recordId)
                throw gone
            } finally {
                refresh.fill('\u0000')
            }
            generation++
        }
    }

    private companion object {
        /** The scheme prefix only — the header name itself comes from `:remote:core`, so the
         * two cannot drift apart and start naming different headers. */
        const val BEARER_PREFIX = "Bearer "
    }
}
