package com.absolutex.remote.cloud

import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One PKCE authorisation attempt (RFC 7636), and the two strings that leave the device with it.
 *
 * PKCE exists so a public client needs no secret: the app sends a *hash* of a freshly generated
 * verifier up front, and proves possession by presenting the verifier when redeeming the code.
 * An attacker who intercepts the redirect gets a code they cannot spend. That is why this lane
 * embeds no client secret anywhere — there is nothing to embed.
 *
 * [verifier] is a CharArray and this is [Closeable] for the reason every secret here is: a
 * plaintext credential with no bounded lifetime is what a heap dump carries away. Use it in a
 * `use` block, or zero it yourself.
 *
 * [state] is CSRF defence, not a secret: it ties the redirect that comes back to the request
 * that went out, so a redirect the app did not start is refused.
 */
class PkceChallenge internal constructor(
    val verifier: CharArray,
    val challenge: String,
    val state: String,
) : Closeable {

    /** The method name providers expect alongside [challenge]. Plain `S256`, never `plain`. */
    val challengeMethod: String get() = CHALLENGE_METHOD

    override fun close() {
        verifier.fill('\u0000')
    }

    companion object {
        const val CHALLENGE_METHOD = "S256"

        /**
         * 32 random bytes, URL-safe-Base64'd into a 43-character verifier — the length RFC 7636
         * recommends, and every character already in its unreserved set, so nothing needs
         * escaping on the way out.
         */
        fun generate(random: SecureRandom = SecureRandom()): PkceChallenge {
            val verifier = randomUrlSafe(random).toCharArray()
            return PkceChallenge(verifier, challengeFor(verifier), randomUrlSafe(random))
        }

        /** BASE64URL(SHA-256(ASCII(verifier))), unpadded, per RFC 7636 §4.2. */
        internal fun challengeFor(verifier: CharArray): String {
            val ascii = ByteArray(verifier.size) { verifier[it].code.toByte() }
            return try {
                ENCODER.encodeToString(MessageDigest.getInstance("SHA-256").digest(ascii))
            } finally {
                // The verifier itself is the caller's to zero; this transient copy is ours.
                ascii.fill(0)
            }
        }

        private fun randomUrlSafe(random: SecureRandom): String {
            val bytes = ByteArray(ENTROPY_BYTES)
            random.nextBytes(bytes)
            return try {
                ENCODER.encodeToString(bytes)
            } finally {
                bytes.fill(0)
            }
        }

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private const val ENTROPY_BYTES = 32
    }
}

/** Where a redirect landed: exactly one of these, so no caller can read a code that is not there. */
sealed interface RedirectResult {
    /** The authorisation code, ready to redeem with the verifier that started this attempt. */
    data class Code(val code: String) : RedirectResult

    /** The user declined at the provider's consent screen. Not an error to report as a failure. */
    data object Denied : RedirectResult

    /** The provider refused. [error] is its code; [description] may be absent. */
    data class Failed(val error: String, val description: String?) : RedirectResult

    /** The redirect did not belong to the attempt we started — refuse it and start again. */
    data object StateMismatch : RedirectResult
}

/**
 * The provider's authorisation URL for [challenge].
 *
 * Every value is percent-encoded here rather than by the caller, because one unescaped `&` in a
 * scope list silently becomes an extra parameter. Spaces encode as `%20`, never `+`: `+` is only
 * a space under form rules, and a provider reading this as a plain URI would take it literally.
 */
fun authorizationUrl(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    scopes: List<String>,
    challenge: PkceChallenge,
    extra: Map<String, String> = emptyMap(),
): String {
    val params = linkedMapOf(
        "client_id" to clientId,
        "response_type" to "code",
        "redirect_uri" to redirectUri,
        "scope" to scopes.joinToString(" "),
        "code_challenge" to challenge.challenge,
        "code_challenge_method" to challenge.challengeMethod,
        "state" to challenge.state,
    )
    params += extra
    val query = params.entries.joinToString("&") { "${encodeParam(it.key)}=${encodeParam(it.value)}" }
    return if (endpoint.contains('?')) "$endpoint&$query" else "$endpoint?$query"
}

/**
 * Reads the redirect the provider sent back, refusing anything that does not belong to the
 * attempt [challenge] started.
 *
 * The state check runs first and in constant time. It is compared against a value an attacker
 * can choose, so a short-circuiting comparison would leak how much of it matched; and checking
 * it before the code means a forged redirect never reaches the token endpoint at all.
 */
fun parseRedirect(redirect: String, challenge: PkceChallenge): RedirectResult {
    val params = queryOf(redirect)
    val state = params["state"].orEmpty()
    if (!constantTimeEquals(state, challenge.state)) return RedirectResult.StateMismatch
    params["error"]?.let { error ->
        return if (error == ACCESS_DENIED) {
            RedirectResult.Denied
        } else {
            RedirectResult.Failed(error, params["error_description"])
        }
    }
    val code = params["code"]
    return if (code.isNullOrEmpty()) {
        RedirectResult.Failed("invalid_response", "redirect carried neither a code nor an error")
    } else {
        RedirectResult.Code(code)
    }
}

/**
 * The redirect's query parameters, decoded once. A repeated parameter keeps its *first* value:
 * a redirect carrying `state` twice is an attempt to have the check read one and the code read
 * the other, so later copies are ignored rather than overwriting.
 */
internal fun queryOf(uri: String): Map<String, String> {
    val query = uri.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    query.split('&').forEach { pair ->
        if (pair.isNotEmpty()) {
            val name = decodeParam(pair.substringBefore('='))
            if (!out.containsKey(name)) out[name] = decodeParam(pair.substringAfter('=', ""))
        }
    }
    return out
}

/** Compares without leaking, through a length-independent digest of each side. */
internal fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
    MessageDigest.getInstance("SHA-256").digest(a.toByteArray(Charsets.UTF_8)),
    MessageDigest.getInstance("SHA-256").digest(b.toByteArray(Charsets.UTF_8)),
)

private fun encodeParam(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun decodeParam(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

private const val ACCESS_DENIED = "access_denied"
