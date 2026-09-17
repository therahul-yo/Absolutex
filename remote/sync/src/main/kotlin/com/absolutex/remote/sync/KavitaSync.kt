package com.absolutex.remote.sync

import com.absolutex.core.data.ProgressDao
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Kavita side of sync, mirroring [KomgaSync]: matching, compare-then-push and pull against
 * one server account. Same secret discipline (load per call, zero in `finally`) and the
 * same never-guess matching rule.
 */
class KavitaSync(
    private val http: HttpCall,
    private val secrets: SyncSecrets,
    private val progressDao: ProgressDao,
    private val clock: ServerClock,
) : ServerSync {

    private val libraryIds = ConcurrentHashMap<Int, Int>()

    /** API-key sessions by server: one exchange per server, not one per book per trigger. */
    private val apiKeySessions = ConcurrentHashMap<String, ApiSession>()

    private data class ApiSession(val token: String, val refreshToken: String)

    /** Full compare-then-push; throws on transport failure (the caller queues). */
    override suspend fun sync(server: SyncServer, local: SyncProgress) {
        val (client, secret, library) = connected(server)
        try {
            val target = matchKavitaFile(local.bookId, library.allChapterFiles()) ?: return
            val remote = client.getProgress(target.first.chapterId)
                ?.atClientTime(server)
                ?.toKavitaSync(local.pageCount)
            if (remote != null && syncDecision(local, remote, SYNC_TOLERANCE_MS) == SyncDecision.PULL) {
                progressDao.get(local.bookId)?.let {
                    progressDao.upsert(it.copy(pageIndex = remote.pageIndex, updatedAt = remote.updatedAt))
                }
                return
            }
            if (remote == null || syncDecision(local, remote, SYNC_TOLERANCE_MS) == SyncDecision.PUSH) {
                // Clamped into the remote chapter: the server carries its own page count and
                // the parsers may disagree with the local file (replaced edition), so a push
                // past the chapter's real last page must be impossible. Falls back to the
                // local count when the server omits the field.
                val lastPage = maxOf(0, (target.second.pages ?: local.pageCount) - 1)
                val pageNum = local.pageIndex.coerceIn(0, lastPage)
                client.saveProgress(
                    KavitaProgress(
                        volumeId = target.first.volumeId,
                        chapterId = target.first.chapterId,
                        pageNum = pageNum,
                        seriesId = target.first.seriesId,
                        libraryId = libraryIdFor(library, target.first.seriesId),
                    ),
                )
            }
        } catch (e: HttpStatusException) {
            // A dead cached token must not poison the next run: evict it so the next call
            // re-exchanges, then let the runner stop the server like any other auth failure.
            if (isAuthFailure(e)) apiKeySessions.remove(server.id)
            throw e
        } finally {
            secret?.fill(Char.MIN_VALUE)
        }
    }

    /** Adoptable remote position, or null when nothing newer exists. Never throws for that. */
    override suspend fun pull(server: SyncServer, local: SyncProgress): SyncProgress? {
        val (client, secret, library) = connected(server)
        try {
            val target = matchKavitaFile(local.bookId, library.allChapterFiles())
            val remote = target?.let { client.getProgress(it.first.chapterId)?.atClientTime(server) }
            val position = remote?.toKavitaSync(local.pageCount)
            return if (position != null && syncDecision(local, position, SYNC_TOLERANCE_MS) == SyncDecision.PULL) {
                position
            } else {
                null
            }
        } catch (e: HttpStatusException) {
            if (isAuthFailure(e)) apiKeySessions.remove(server.id)
            throw e
        } finally {
            secret?.fill(Char.MIN_VALUE)
        }
    }

    private data class Connected(
        val client: KavitaClient,
        val secret: CharArray?,
        val library: KavitaLibrary,
    )

    companion object {
        internal const val KAVITA_PLUGIN_NAME = "Absolutex"
    }

    private suspend fun connected(server: SyncServer): Connected {
        val call = http.withClock(server.id, clock).withCleartextPolicy(server.allowCleartext)
        val client = KavitaClient(call, server.baseUrl)
        if (server.usesApiKey) {
            return apiKeyClient(server, client)
        }
        return loginClient(server, client)
    }

    /**
     * Server stamps arrive on the server's clock; decisions below run on ours. Converted once
     * here, at the observation boundary — except absent progress (page 0 at epoch 0), which
     * converts by identity so the missing-not-page-one rule in [toKavitaSync] still sees it.
     */
    private fun RemoteProgress.atClientTime(server: SyncServer): RemoteProgress {
        if (page == 0 && updatedAt == 0L) return this
        return copy(updatedAt = clock.toClientTime(server.id, updatedAt))
    }

    private fun apiKeyClient(server: SyncServer, client: KavitaClient): Connected {
        // Auth keys exchange for a JWT via /api/Plugin/authenticate (verified against
        // Kavita's source + OpenAPI) — the raw key is not a bearer token and 401s as one.
        // The session caches per server: one exchange, not one per book per trigger. A 401
        // first runs the client's refresh (exercising that path); only a dead refresh
        // evicts, so the next run re-exchanges instead of serving a dead token forever.
        apiKeySessions[server.id]?.let { session ->
            client.restoreSession(session.token, session.refreshToken)
            return Connected(client, null, KavitaLibrary(client))
        }
        val key = secrets.loadApiKey(server.id) ?: throw IOException("no API key for ${server.id}")
        try {
            client.exchangeApiKey(key, KAVITA_PLUGIN_NAME)
        } finally {
            key.fill(Char.MIN_VALUE)
        }
        val session = exchangedSession(client)
        apiKeySessions[server.id] = session
        return Connected(client, null, KavitaLibrary(client))
    }

    private fun exchangedSession(client: KavitaClient): ApiSession {
        val token = client.token ?: throw IOException("kavita API-key exchange returned no token")
        val refreshToken = client.refreshToken ?: throw IOException("kavita API-key exchange returned no token")
        return ApiSession(token, refreshToken)
    }

    private suspend fun loginClient(server: SyncServer, client: KavitaClient): Connected {
        val username = server.username ?: throw IOException("server has no credentials: ${server.id}")
        val password = secrets.loadPassword(server.id) ?: throw IOException("no password for ${server.id}")
        client.login(username, password)
        return Connected(client, password, KavitaLibrary(client))
    }

    private suspend fun libraryIdFor(library: KavitaLibrary, seriesId: Int): Int {
        libraryIds[seriesId]?.let { return it }
        return library.seriesLibraryId(seriesId).also { libraryIds[seriesId] = it }
    }

    /**
     * Kavita answers absent progress as page 0 at epoch 0: adopting that over a real local
     * position would rewind every fresh book, so it reads as missing, not as page one.
     */
    private fun RemoteProgress.toKavitaSync(pageCount: Int): SyncProgress? {
        if (updatedAt == 0L && page == 0) return null
        return SyncProgress(
            bookId = "",
            pageIndex = kavitaPageToIndex(page).coerceIn(0, maxOf(0, pageCount - 1)),
            pageCount = pageCount,
            updatedAt = updatedAt,
        )
    }
}
