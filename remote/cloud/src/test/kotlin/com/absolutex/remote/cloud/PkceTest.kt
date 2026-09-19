package com.absolutex.remote.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.junit.Test

/**
 * PKCE (RFC 7636) and the redirect that comes back.
 *
 * The verifier/challenge pair is checked against the RFC's own definition rather than against
 * what the implementation happens to produce — a test that recomputes the challenge the same
 * way the code does would pass with the hash swapped for a no-op.
 */
class PkceTest {

    /**
     * Deterministic but *different* on every draw. A random that returned the same bytes each
     * time could not tell "two draws" from "one draw reused" — both would come back equal — so
     * the counter is what gives the test below its teeth.
     */
    private class CountingRandom : SecureRandom() {
        private var draw: Byte = 0

        override fun nextBytes(bytes: ByteArray) {
            draw++
            bytes.fill(draw)
        }
    }

    // --- verifier and challenge ----------------------------------------------------------

    @Test fun `the verifier is 43 unreserved characters, as the RFC recommends`() {
        PkceChallenge.generate().use { pkce ->
            assertEquals(43, pkce.verifier.size)
            val unreserved = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')
            assertTrue(
                "verifier must need no escaping: ${pkce.verifier.concatToString()}",
                pkce.verifier.all { it in unreserved },
            )
        }
    }

    @Test fun `the challenge is the RFC's BASE64URL of SHA-256 of the verifier`() {
        PkceChallenge.generate().use { pkce ->
            // Computed here from the RFC's definition, independently of the implementation.
            val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(pkce.verifier.concatToString().toByteArray(Charsets.US_ASCII)),
            )
            assertEquals(expected, pkce.challenge)
        }
    }

    @Test fun `the RFC's own worked example reproduces its published challenge`() {
        // RFC 7636 appendix B: this verifier must produce exactly this challenge. If our
        // encoding drifted — padding, standard alphabet, the wrong digest — this is what
        // catches it, because the expected value comes from the spec and not from us.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".toCharArray()
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", PkceChallenge.challengeFor(verifier))
    }

    @Test fun `the challenge method is S256, never plain`() {
        PkceChallenge.generate().use { assertEquals("S256", it.challengeMethod) }
    }

    @Test fun `two attempts never share a verifier or a state`() {
        PkceChallenge.generate().use { first ->
            PkceChallenge.generate().use { second ->
                assertNotEquals(first.verifier.concatToString(), second.verifier.concatToString())
                assertNotEquals(first.state, second.state)
                assertNotEquals(first.challenge, second.challenge)
            }
        }
    }

    @Test fun `the verifier and the state come from different draws`() {
        // Both are 32 random bytes; drawing them from one call would make state a copy of the
        // verifier, handing the secret to the provider in the clear.
        PkceChallenge.generate(CountingRandom()).use { pkce ->
            assertNotEquals(pkce.verifier.concatToString(), pkce.state)
        }
    }

    @Test fun `closing zeroes the verifier`() {
        val pkce = PkceChallenge.generate()
        pkce.close()
        assertTrue("verifier survived close()", pkce.verifier.all { it == '\u0000' })
    }

    // --- authorisation URL ----------------------------------------------------------------

    @Test fun `the authorisation url carries the challenge, not the verifier`() {
        PkceChallenge.generate().use { pkce ->
            val url = authorizationUrl(
                endpoint = "https://login.example/authorize",
                clientId = "client-123",
                redirectUri = "absolutex://oauth",
                scopes = listOf("Files.Read", "offline_access"),
                challenge = pkce,
            )
            val params = queryOf(url)
            assertEquals(pkce.challenge, params["code_challenge"])
            assertEquals("S256", params["code_challenge_method"])
            assertEquals("code", params["response_type"])
            assertEquals("client-123", params["client_id"])
            assertEquals("absolutex://oauth", params["redirect_uri"])
            assertEquals(pkce.state, params["state"])
            // The one thing that must never leave the device.
            assertTrue("the verifier leaked into the url", !url.contains(pkce.verifier.concatToString()))
        }
    }

    @Test fun `scopes are space-joined and escaped, so one scope cannot become two parameters`() {
        PkceChallenge.generate().use { pkce ->
            val url = authorizationUrl(
                "https://login.example/authorize", "c", "absolutex://oauth",
                listOf("Files.Read", "offline_access"), pkce,
            )
            assertTrue("space must not encode as +: $url", !url.contains("scope=Files.Read+"))
            assertEquals("Files.Read offline_access", queryOf(url)["scope"])
        }
    }

    @Test fun `a hostile value cannot inject another parameter`() {
        PkceChallenge.generate().use { pkce ->
            val url = authorizationUrl(
                "https://login.example/authorize", "c&redirect_uri=https://evil.example",
                "absolutex://oauth", listOf("s"), pkce,
            )
            // Decoded back it is still one value, not a second parameter.
            assertEquals("c&redirect_uri=https://evil.example", queryOf(url)["client_id"])
            assertEquals("absolutex://oauth", queryOf(url)["redirect_uri"])
        }
    }

    @Test fun `an endpoint that already has a query gets joined with an ampersand`() {
        PkceChallenge.generate().use { pkce ->
            val url = authorizationUrl("https://login.example/authorize?tenant=x", "c", "r", listOf("s"), pkce)
            assertEquals("x", queryOf(url)["tenant"])
            assertEquals("c", queryOf(url)["client_id"])
        }
    }

    // --- redirect -------------------------------------------------------------------------

    @Test fun `a matching redirect yields the code`() {
        PkceChallenge.generate().use { pkce ->
            val result = parseRedirect("absolutex://oauth?code=abc123&state=${pkce.state}", pkce)
            assertEquals(RedirectResult.Code("abc123"), result)
        }
    }

    @Test fun `a redirect for another attempt is refused before its code is read`() {
        PkceChallenge.generate().use { pkce ->
            assertEquals(
                RedirectResult.StateMismatch,
                parseRedirect("absolutex://oauth?code=abc123&state=someone-elses", pkce),
            )
        }
    }

    @Test fun `a redirect with no state at all is refused`() {
        PkceChallenge.generate().use { pkce ->
            assertEquals(RedirectResult.StateMismatch, parseRedirect("absolutex://oauth?code=abc123", pkce))
        }
    }

    @Test fun `a duplicated state parameter cannot smuggle a second value past the check`() {
        PkceChallenge.generate().use { pkce ->
            // First wins, so appending the real state after a forged one does not rescue it.
            assertEquals(
                RedirectResult.StateMismatch,
                parseRedirect("absolutex://oauth?state=forged&code=abc&state=${pkce.state}", pkce),
            )
        }
    }

    @Test fun `a declined consent screen is a decline, not a failure`() {
        PkceChallenge.generate().use { pkce ->
            assertEquals(
                RedirectResult.Denied,
                parseRedirect("absolutex://oauth?error=access_denied&state=${pkce.state}", pkce),
            )
        }
    }

    @Test fun `a provider error keeps its code and description`() {
        PkceChallenge.generate().use { pkce ->
            val result = parseRedirect(
                "absolutex://oauth?error=invalid_scope&error_description=Bad%20scope&state=${pkce.state}",
                pkce,
            )
            assertEquals(RedirectResult.Failed("invalid_scope", "Bad scope"), result)
        }
    }

    @Test fun `a redirect with neither code nor error is a failure, never an empty code`() {
        PkceChallenge.generate().use { pkce ->
            val result = parseRedirect("absolutex://oauth?state=${pkce.state}", pkce)
            assertTrue("got $result", result is RedirectResult.Failed)
        }
    }

    @Test fun `an escaped code is decoded exactly once`() {
        PkceChallenge.generate().use { pkce ->
            val result = parseRedirect("absolutex://oauth?code=a%2Bb%2Fc%3D&state=${pkce.state}", pkce)
            assertEquals(RedirectResult.Code("a+b/c="), result)
        }
    }

    @Test fun `state comparison does not depend on length`() {
        PkceChallenge.generate().use { pkce ->
            assertTrue(constantTimeEquals(pkce.state, pkce.state))
            assertTrue(!constantTimeEquals(pkce.state, pkce.state.dropLast(1)))
            assertTrue(!constantTimeEquals(pkce.state, pkce.state + "x"))
        }
    }
}
