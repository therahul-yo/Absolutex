package com.absolutex.remote.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.syncQueueStore by preferencesDataStore(name = "sync_queue")

/** One unsent position. Entries are keyed by (serverId, bookId); newer progress replaces. */
data class PendingPush(
    val serverId: String,
    val bookId: String,
    val pageIndex: Int,
    val pageCount: Int,
    val updatedAt: Long,
    val attempts: Int = 0,
    val nextDueAt: Long = 0L,
    /** Last transport error, for future diagnostics UI. Never thrown, only recorded. */
    val lastError: String? = null,
)

/**
 * Capped exponential backoff. The delay is bounded ([MAX_DELAY_MS]) and the shift is capped,
 * so the sequence is finite-state and provably bounded no matter how long a server stays
 * down; entries persist (see [SyncQueue]) and are retried on every trigger once due, so a
 * parked push is delayed, never silently dropped.
 */
object BackoffPolicy {
    const val BASE_DELAY_MS = 30_000L
    const val MAX_DELAY_MS = 4 * 3_600_000L
    private const val MAX_SHIFT = 20

    fun delayFor(attempts: Int): Long =
        minOf(BASE_DELAY_MS shl attempts.coerceIn(0, MAX_SHIFT), MAX_DELAY_MS)
}

/**
 * Persisted push outbox (DataStore, one JSON document). A failed push is queued and retried
 * with [BackoffPolicy]; triggers (app start, book close, page settle, manual) drain whatever
 * is due. Survives process death by construction — it IS the disk.
 */
class SyncQueue(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.syncQueueStore)

    val pending: Flow<List<PendingPush>> = store.data.map { prefs ->
        parseQueue(prefs[QUEUE_KEY])
    }

    suspend fun enqueue(entry: PendingPush, error: String? = null) {
        store.edit { prefs ->
            val kept = parseQueue(prefs[QUEUE_KEY]).filterNot {
                it.serverId == entry.serverId && it.bookId == entry.bookId
            }
            // Fresh progress is new work: attempts reset, due now.
            prefs[QUEUE_KEY] = serialise(kept + entry.copy(attempts = 0, nextDueAt = 0L, lastError = error))
        }
    }

    suspend fun due(now: Long): List<PendingPush> =
        pending.first().filter { it.nextDueAt <= now }

    suspend fun recordFailure(entry: PendingPush, now: Long, error: String?) {
        store.edit { prefs ->
            val kept = parseQueue(prefs[QUEUE_KEY]).filterNot {
                it.serverId == entry.serverId && it.bookId == entry.bookId
            }
            val attempts = entry.attempts + 1
            prefs[QUEUE_KEY] = serialise(
                kept + entry.copy(
                    attempts = attempts,
                    nextDueAt = now + BackoffPolicy.delayFor(attempts),
                    lastError = error,
                ),
            )
        }
    }

    suspend fun remove(serverId: String, bookId: String) {
        store.edit { prefs ->
            prefs[QUEUE_KEY] = serialise(
                parseQueue(prefs[QUEUE_KEY]).filterNot { it.serverId == serverId && it.bookId == bookId },
            )
        }
    }

    suspend fun clear() {
        store.edit { prefs -> prefs.remove(QUEUE_KEY) }
    }

    private fun serialise(queue: List<PendingPush>): String = JSONArray(
        queue.map { entry ->
            JSONObject()
                .put("serverId", entry.serverId)
                .put("bookId", entry.bookId)
                .put("pageIndex", entry.pageIndex)
                .put("pageCount", entry.pageCount)
                .put("updatedAt", entry.updatedAt)
                .put("attempts", entry.attempts)
                .put("nextDueAt", entry.nextDueAt)
                .put("lastError", entry.lastError)
        },
    ).toString()

    private fun parseQueue(raw: String?): List<PendingPush> {
        val array = runCatching { JSONArray(raw ?: "") }.getOrNull() ?: return emptyList()
        return List(array.length(), array::getJSONObject).mapNotNull { obj ->
            val serverId = optString(obj, "serverId") ?: return@mapNotNull null
            val bookId = optString(obj, "bookId") ?: return@mapNotNull null
            val pageIndex = optInt(obj, "pageIndex") ?: return@mapNotNull null
            val pageCount = optInt(obj, "pageCount") ?: return@mapNotNull null
            val updatedAt = optLong(obj, "updatedAt") ?: return@mapNotNull null
            PendingPush(
                serverId = serverId,
                bookId = bookId,
                pageIndex = pageIndex,
                pageCount = pageCount,
                updatedAt = updatedAt,
                attempts = optInt(obj, "attempts") ?: 0,
                nextDueAt = optLong(obj, "nextDueAt") ?: 0L,
                lastError = optString(obj, "lastError"),
            )
        }
    }

    companion object {
        private val QUEUE_KEY = stringPreferencesKey("queue")
    }
}
