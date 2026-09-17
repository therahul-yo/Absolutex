package com.absolutex.remote.sync

import java.io.IOException
import org.json.JSONObject

/**
 * Live connection test for a Komga record, for the servers form's test-then-save.
 *
 * Credentials ride as parameters (never the store): the form tests before anything is
 * saved. One minimal probe — the first page of the series list — proves reachability, auth
 * and protocol at once; the body must also parse as a Komga page, so a foreign server
 * answering 200 with HTML reads as [ConnectionResult.NotFound], not Ok.
 */
class KomgaConnectionProbe(private val http: HttpCall) {

    suspend fun test(baseUrl: String, auth: KomgaAuth): ConnectionResult {
        val root = baseUrl.trimEnd('/')
        val headers = when (auth) {
            is KomgaAuth.Basic -> mapOf(AUTHORIZATION to basicCredentials(auth.username, auth.password))
            is KomgaAuth.ApiKey -> mapOf(API_KEY_HEADER to auth.key.concatToString())
        }
        return try {
            val response = http.request(
                "POST",
                "$root/api/v1/series/list?page=0&size=1",
                headers + JSON_HEADERS,
                "{}",
            )
            when (response.code) {
                HTTP_OK -> {
                    parseSeriesPage(response.body)
                    ConnectionResult.Ok
                }
                HTTP_UNAUTHORIZED -> ConnectionResult.AuthFailed
                HTTP_NOT_FOUND -> ConnectionResult.NotFound
                else -> ConnectionResult.Unreachable
            }
        } catch (e: IOException) {
            if (e.message?.startsWith("malformed JSON") == true) {
                ConnectionResult.NotFound
            } else {
                mapProbeFailure(e)
            }
        }
    }
}

/**
 * Live connection test for a Kavita record: login (or API-key exchange), then the library
 * list. Same parameter-carried credentials and shape-proving as [KomgaConnectionProbe].
 */
class KavitaConnectionProbe(private val http: HttpCall) {

    /** Credentials for one test run; mirror the two supported Kavita auth paths. */
    sealed interface Credentials {
        data class Login(val username: String, val password: CharArray) : Credentials
        data class ApiKey(val key: CharArray) : Credentials
    }

    suspend fun test(baseUrl: String, credentials: Credentials): ConnectionResult {
        val root = baseUrl.trimEnd('/')
        return try {
            val token = when (credentials) {
                is Credentials.Login -> loginToken(root, credentials)
                is Credentials.ApiKey -> exchangeToken(root, credentials)
            }
            if (token == null) {
                ConnectionResult.AuthFailed
            } else {
                probeLibraries(root, token)
            }
        } catch (e: IOException) {
            if (e.message?.startsWith("malformed JSON") == true) {
                ConnectionResult.NotFound
            } else {
                mapProbeFailure(e)
            }
        }
    }

    private fun probeLibraries(root: String, token: String): ConnectionResult {
        val libraries = http.request(
            "GET",
            "$root/api/Library/libraries",
            mapOf(AUTHORIZATION to "Bearer $token"),
            null,
        )
        return when (libraries.code) {
            HTTP_OK -> {
                parseKavitaLibraries(libraries.body)
                ConnectionResult.Ok
            }
            HTTP_UNAUTHORIZED -> ConnectionResult.AuthFailed
            HTTP_NOT_FOUND -> ConnectionResult.NotFound
            else -> ConnectionResult.Unreachable
        }
    }

    private fun loginToken(root: String, credentials: Credentials.Login): String? {
        val body = JSONObject()
            .put("username", credentials.username)
            .put("password", credentials.password.concatToString())
            .toString()
        val response = http.request("POST", "$root/api/Account/login", JSON_HEADERS, body)
        if (response.code == HTTP_UNAUTHORIZED) return null
        if (response.code != HTTP_OK) throw IOException("kavita login probe failed: ${response.code}")
        return parseKavitaSession(response.body).token
    }

    private fun exchangeToken(root: String, credentials: Credentials.ApiKey): String? {
        val encoded = java.net.URLEncoder.encode(credentials.key.concatToString(), Charsets.UTF_8)
        val response = http.request(
            "POST",
            "$root/api/Plugin/authenticate?apiKey=$encoded&pluginName=${KavitaSync.KAVITA_PLUGIN_NAME}",
            JSON_HEADERS,
            null,
        )
        if (response.code == HTTP_UNAUTHORIZED) return null
        if (response.code != HTTP_OK) throw IOException("kavita API-key probe failed: ${response.code}")
        return parseKavitaSession(response.body).token
    }
}
