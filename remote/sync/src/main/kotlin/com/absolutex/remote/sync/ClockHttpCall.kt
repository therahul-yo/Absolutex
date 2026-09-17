package com.absolutex.remote.sync

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

    override fun requestBytes(method: String, url: String, headers: Map<String, String>): HttpBytesResponse {
        val response = delegate.requestBytes(method, url, headers)
        response.serverDateMs?.let(onServerDate)
        return response
    }
}

/** Observes [this] call's server dates into [clock]'s slot for [serverId]. */
fun HttpCall.withClock(serverId: String, clock: ServerClock): HttpCall =
    ClockHttpCall(this) { dateMs -> clock.noteServerDate(serverId, dateMs) }
