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
) : ServerSync {

    private val libraryIds = ConcurrentHashMap<Int, Int>()

    /** Full compare-then-push; throws on transport failure (the caller queues). */
    override suspend fun sync(server: SyncServer, local: SyncProgress) {
        val (client, secret, library) = connected(server)
        try {
            val target = matchKavitaFile(local.bookId, library.allChapterFiles()) ?: return
            val remote = client.getProgress(target.first.chapterId)?.toKavitaSync(local.pageCount)
            if (remote != null && syncDecision(local, remote) == SyncDecision.PULL) {
                progressDao.get(local.bookId)?.let {
                    progressDao.upsert(it.copy(pageIndex = remote.pageIndex, updatedAt = remote.updatedAt))
                }
                return
            }
            if (remote == null || syncDecision(local, remote) == SyncDecision.PUSH) {
                client.saveProgress(
                    KavitaProgress(
                        volumeId = target.first.volumeId,
                        chapterId = target.first.chapterId,
                        pageNum = local.pageIndex,
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
            val remote = target?.let { client.getProgress(it.first.chapterId) }
            val position = remote?.toKavitaSync(local.pageCount)
            return if (position != null && syncDecision(local, position) == SyncDecision.PULL) {
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

    private suspend fun connected(server: SyncServer): Connected {
        val client = KavitaClient(http, server.baseUrl)
        if (server.usesApiKey) {
            // API keys ride as bearer tokens (assumption — the lead validates on a live
            // server); a rejected key surfaces as 401 on first use, never silently.
            val key = secrets.loadApiKey(server.id) ?: throw IOException("no API key for ${server.id}")
            client.setBearerToken(key.concatToString())
            return Connected(client, key, KavitaLibrary(client))
        }
        return loginClient(server, client)
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
