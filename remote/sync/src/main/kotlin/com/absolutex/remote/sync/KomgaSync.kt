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
    private val clock: ServerClock,
) : ServerSync {

    /** Full compare-then-push; throws on transport failure (the caller queues). */
    override suspend fun sync(server: SyncServer, local: SyncProgress) {
        val (client, secret) = clientFor(server)
        try {
            val target = matchKomgaBook(local.bookId, bookMap(client)) ?: return
            val remote = client.getProgress(target.id)?.atClientTime(server)?.toKomgaSync(local.pageCount)
            if (remote != null && syncDecision(local, remote, SYNC_TOLERANCE_MS) == SyncDecision.PULL) {
                progressDao.get(local.bookId)?.let {
                    progressDao.upsert(it.copy(pageIndex = remote.pageIndex, updatedAt = remote.updatedAt))
                }
                return
            }
            if (remote == null || syncDecision(local, remote, SYNC_TOLERANCE_MS) == SyncDecision.PUSH) {
                // Clamped into the remote book: a local index past the server's page count
                // (re-scanned file, replaced edition) must not push a page that does not exist.
                val page = komgaIndexToPage(local.pageIndex)
                    .coerceIn(KOMGA_FIRST_PAGE, maxOf(target.pageCount, KOMGA_FIRST_PAGE))
                client.putProgress(target.id, page, local.pageIndex >= local.pageCount - 1)
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
            val remote = target?.let { client.getProgress(it.id)?.atClientTime(server) }
            val position = remote?.toKomgaSync(local.pageCount)
            return if (position != null && syncDecision(local, position, SYNC_TOLERANCE_MS) == SyncDecision.PULL) {
                position
            } else {
                null
            }
        } finally {
            secret?.fill(Char.MIN_VALUE)
        }
    }

    /**
     * Server stamps arrive on the server's clock; decisions below run on ours. Converted once
     * here, at the observation boundary — adopting the converted value keeps later runs
     * consistent without ever double-shifting.
     */
    private fun RemoteProgress.atClientTime(server: SyncServer): RemoteProgress =
        copy(updatedAt = clock.toClientTime(server.id, updatedAt))

    private fun clientFor(server: SyncServer): Pair<KomgaClient, CharArray?> =
        if (server.usesApiKey) {
            apiKeyClient(server)
        } else {
            basicClient(server)
        }

    private fun apiKeyClient(server: SyncServer): Pair<KomgaClient, CharArray?> {
        val key = secrets.loadApiKey(server.id) ?: throw IOException("no API key for ${server.id}")
        return KomgaClient(clockedHttp(server), server.baseUrl, KomgaAuth.ApiKey(key)) to key
    }

    private fun basicClient(server: SyncServer): Pair<KomgaClient, CharArray?> {
        val username = server.username ?: throw IOException("server has no credentials: ${server.id}")
        val password = secrets.loadPassword(server.id) ?: throw IOException("no password for ${server.id}")
        return KomgaClient(clockedHttp(server), server.baseUrl, KomgaAuth.Basic(username, password)) to password
    }

    /**
     * Every response's server `Date` feeds this server's clock slot (see [ServerClock]), so
     * by decision time the offset is measured, not assumed — and every request re-checks
     * the cleartext opt-in, including redirect hops the platform must never follow blindly.
     * The clients stay clock- and policy-unaware.
     */
    private fun clockedHttp(server: SyncServer): HttpCall =
        http.withClock(server.id, clock).withCleartextPolicy(server.allowCleartext)

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
