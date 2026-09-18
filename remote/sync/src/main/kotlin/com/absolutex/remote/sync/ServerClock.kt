package com.absolutex.remote.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-server clock skew, observed from `Date` response headers.
 *
 * Neither client sends a client timestamp — Komga and Kavita stamp progress with their own
 * clocks — so comparing the phone's `updatedAt` against a server stamp directly misorders
 * whenever the clocks disagree (a phone two hours fast pushes a stale page over a genuinely
 * newer one, and the other device then adopts the regression). Every response carrying a
 * server date updates that server's offset; remote stamps convert to client time at the
 * observation boundary, so all comparisons below run on one clock and an offset can never be
 * applied twice.
 *
 * In-memory only: offsets re-derive within one response of any sync run, so persisting them
 * would buy nothing and risk acting on a stale correction after either clock jumps (NTP step,
 * timezone change, server migration).
 */
class ServerClock {

    private val offsetsMs = ConcurrentHashMap<String, Long>()

    /** Records that [serverId]'s clock read [serverDateMs] when ours read [nowMs]. */
    fun noteServerDate(serverId: String, serverDateMs: Long, nowMs: Long = System.currentTimeMillis()) {
        offsetsMs[serverId] = serverDateMs - nowMs
    }

    /** Last observed offset (server minus client), or 0 when no dated response arrived yet. */
    fun offsetMs(serverId: String): Long = offsetsMs[serverId] ?: 0L

    /** Converts a stamp from [serverId]'s clock to ours. Unknown servers convert by identity. */
    fun toClientTime(serverId: String, serverMs: Long): Long = serverMs - offsetMs(serverId)
}
