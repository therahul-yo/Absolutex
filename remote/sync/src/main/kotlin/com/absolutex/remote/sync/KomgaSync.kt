package com.absolutex.remote.sync

import com.absolutex.core.data.ProgressDao
import java.io.IOException

/**
 * Komga side of sync: matching, compare-then-push and pull against one server account.
 * Secrets load per call and zero in `finally`; the client is rebuilt per call so tokens and
 * auth headers never outlive the run that needed them.
 */
class KomgaSync(
    private val http: HttpCall,
    private val secrets: SyncSecrets,
    private val progressDao: ProgressDao,
) : ServerSync {

    /** Full compare-then-push; throws on transport failure (the caller queues). */
    override suspend fun sync(server: SyncServer, local: SyncProgress) {
        val (client, secret) = clientFor(server)
        try {
            val target = matchKomgaBook(local.bookId, bookMap(client)) ?: return
            val remote = client.getProgress(target.id)?.toKomgaSync(local.pageCount)
            if (remote != null && syncDecision(local, remote) == SyncDecision.PULL) {
                progressDao.get(local.bookId)?.let {
                    progressDao.upsert(it.copy(pageIndex = remote.pageIndex, updatedAt = remote.updatedAt))
                }
                return
            }
            if (remote == null || syncDecision(local, remote) == SyncDecision.PUSH) {
                client.putProgress(
                    target.id,
                    komgaIndexToPage(local.pageIndex),
                    local.pageIndex >= local.pageCount - 1,
                )
            }
        } finally {
            secret?.fill(Char.MIN_VALUE)
        }
    }

    /** Adoptable remote position, or null when nothing newer exists. Never throws for that. */
    override suspend fun pull(server: SyncServer, local: SyncProgress): SyncProgress? {
        val (client, secret) = clientFor(server)
        try {
            val target = matchKomgaBook(local.bookId, bookMap(client))
            val remote = target?.let { client.getProgress(it.id) }
            val position = remote?.toKomgaSync(local.pageCount)
            return if (position != null && syncDecision(local, position) == SyncDecision.PULL) {
                position
            } else {
                null
            }
        } finally {
            secret?.fill(Char.MIN_VALUE)
        }
    }

    private fun clientFor(server: SyncServer): Pair<KomgaClient, CharArray?> =
        if (server.usesApiKey) {
            apiKeyClient(server)
        } else {
            basicClient(server)
        }

    private fun apiKeyClient(server: SyncServer): Pair<KomgaClient, CharArray?> {
        val key = secrets.loadApiKey(server.id) ?: throw IOException("no API key for ${server.id}")
        return KomgaClient(http, server.baseUrl, KomgaAuth.ApiKey(key)) to key
    }

    private fun basicClient(server: SyncServer): Pair<KomgaClient, CharArray?> {
        val username = server.username ?: throw IOException("server has no credentials: ${server.id}")
        val password = secrets.loadPassword(server.id) ?: throw IOException("no password for ${server.id}")
        return KomgaClient(http, server.baseUrl, KomgaAuth.Basic(username, password)) to password
    }

    private suspend fun bookMap(client: KomgaClient): List<BookRef> =
        client.listAllSeries().flatMap { client.listAllBooksInSeries(it.id) }

    /** Komga pages count from 1; clamps into the local book for positioning. */
    private fun RemoteProgress.toKomgaSync(pageCount: Int): SyncProgress =
        SyncProgress(
            bookId = "",
            pageIndex = komgaPageToIndex(page).coerceIn(0, maxOf(0, pageCount - 1)),
            pageCount = pageCount,
            updatedAt = updatedAt,
        )
}
