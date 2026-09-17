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

    /** Full compare-then-push; throws on transport failure (the caller queues). */
    override suspend fun sync(server: SyncServer, local: SyncProgress) {
        val (client, secret, library) = connected(server)
        try {
            val target = matchKavitaFile(local.bookId, library.allChapterFiles()) ?: return
            val remote = client.getProgress(target.first.chapterId)?.atClientTime(server)?.toKavitaSync(local.pageCount)
            if (remote != null && syncDecision(local, remote, SYNC_TOLERANCE_MS) == SyncDecision.PULL) {
                progressDao.get(local.bookId)?.let {
                    progressDao.upsert(it.copy(pageIndex = remote.pageIndex, updatedAt = remote.updatedAt))
                }
                return
            }
            if (remote == null || syncDecision(local, remote, SYNC_TOLERANCE_MS) == SyncDecision.PUSH) {
                // Clamped into the local book: pageNum past the last page must not push a
                // position the server (or a later pull) cannot land on.
                val pageNum = local.pageIndex.coerceIn(0, maxOf(0, local.pageCount - 1))
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
        val client = KavitaClient(http.withClock(server.id, clock), server.baseUrl)
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
        val key = secrets.loadApiKey(server.id) ?: throw IOException("no API key for ${server.id}")
        client.exchangeApiKey(key, KAVITA_PLUGIN_NAME)
        return Connected(client, key, KavitaLibrary(client))
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
