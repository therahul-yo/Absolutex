package com.absolutex.remote.sync

import com.absolutex.remote.core.HttpBytesResponse
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import java.io.IOException

/** Scripted HttpCall: enqueue responses, then assert on the recorded requests. */
class FakeHttpCall : HttpCall {
    data class Recorded(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    val requests = mutableListOf<Recorded>()
    private val textQueue = ArrayDeque<HttpResponse>()
    private val bytesQueue = ArrayDeque<HttpBytesResponse>()

    val last: Recorded get() = requests.last()

    fun enqueue(response: HttpResponse): Unit {
        textQueue += response
    }

    fun enqueueBytes(response: HttpBytesResponse): Unit {
        bytesQueue += response
    }

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        requests += Recorded(method, url, headers, body)
        return textQueue.removeFirstOrNull() ?: throw IOException("no queued text response")
    }

    override fun requestBytes(method: String, url: String, headers: Map<String, String>): HttpBytesResponse {
        requests += Recorded(method, url, headers, null)
        return bytesQueue.removeFirstOrNull() ?: throw IOException("no queued bytes response")
    }
}
