package com.absolutex.remote.sync

import com.absolutex.remote.core.HttpBytesResponse
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import com.absolutex.remote.core.HttpStreamResponse

/**
 * [HttpCall] decorator feeding every observed server `Date` into one server's clock slot.
 * Constructed per server per run (see KomgaSync/KavitaSync): the listener already knows its
 * server id, so the clients below stay clock-unaware and their signatures do not change.
 */
class ClockHttpCall(
    private val delegate: HttpCall,
    private val onServerDate: (Long) -> Unit,
) : HttpCall {

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val response = delegate.request(method, url, headers, body)
        response.serverDateMs?.let(onServerDate)
        return response
    }

    override fun requestBytes(
        method: String,
        url: String,
        headers: Map<String, String>,
        maxBytes: Long,
    ): HttpBytesResponse {
        val response = delegate.requestBytes(method, url, headers, maxBytes)
        response.serverDateMs?.let(onServerDate)
        return response
    }

    /**
     * Observed like the other two: the `Date` header arrives with the status, long before
     * the body is read, so a streamed response carries the server clock just as well. The
     * response is returned open — the caller owns closing it, not this decorator.
     */
    override fun requestStream(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): HttpStreamResponse {
        val response = delegate.requestStream(method, url, headers)
        response.serverDateMs?.let(onServerDate)
        return response
    }
}

/** Observes [this] call's server dates into [clock]'s slot for [serverId]. */
fun HttpCall.withClock(serverId: String, clock: ServerClock): HttpCall =
    ClockHttpCall(this) { dateMs -> clock.noteServerDate(serverId, dateMs) }
