package com.absolutex.remote.sync

import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * The sync mechanics behind [SyncController]'s triggers: pushing, pulling, adopting and the
 * retry outbox. Split out so the controller stays a thin trigger surface; everything here
 * runs on the caller's dispatcher (the controller confines it to Dispatchers.IO), and every
 * network failure surfaces as IOException for the queue. Open-book tracking and offers live
 * on the controller — the runner only takes them as arguments.
 */
class SyncRunner(
    private val progressDao: ProgressDao,
    private val servers: RemoteServers,
    private val queue: SyncQueue,
    private val komga: ServerSync,
    private val kavita: ServerSync,
) {
    suspend fun pushBook(bookId: String) {
        val local = progressDao.get(bookId)?.toSync() ?: return
        var queued = false
        for (server in servers.current().mapNotNull { it.toSyncServer() }) {
            try {
                runnerFor(server).sync(server, local)
            } catch (e: IOException) {
                queue.enqueue(local.toPending(server.id), e.message)
                queued = true
            }
        }
        if (queued) drainQueue()
    }

    /**
     * Pulls one book. With [offerWhenOpen], a newer remote for the already-open book is
     * reported through [onOffer] (and null returns) instead of moving anything; without it
     * (pre-first-paint open) the position returns directly and the caller owns what happens
     * next. Otherwise the adoption lands in the database and returns too.
     */
    suspend fun pullBook(
        bookId: String,
        offerWhenOpen: Boolean,
        openBookId: String?,
        onOffer: (RemoteProgressOffer) -> Unit,
    ): SyncProgress? {
        val local = progressDao.get(bookId)?.toSync() ?: return null
        for (server in servers.current().mapNotNull { it.toSyncServer() }) {
            val pulled = runnerFor(server).pull(server, local) ?: continue
            val offered = openBookId == bookId && offerWhenOpen
            if (offered) {
                onOffer(RemoteProgressOffer(bookId, pulled.pageIndex, server.id, server.baseUrl))
            } else if (openBookId != bookId) {
                adopt(local.toProgress(), pulled)
            }
            return if (offered) null else pulled
        }
        return null
    }

    suspend fun pullKnownBooks(openBookId: String?, onOffer: (RemoteProgressOffer) -> Unit) {
        val locals = progressDao.observeAll().first()
        for (server in servers.current().mapNotNull { it.toSyncServer() }) {
            pullFromServer(server, locals, openBookId, onOffer)
        }
    }

    /** Folder browse / refresh for one server: its outbox, then a pull through it. */
    suspend fun syncServer(serverId: String, openBookId: String?, onOffer: (RemoteProgressOffer) -> Unit) {
        val server = servers.current().firstOrNull { it.id == serverId }?.toSyncServer() ?: return
        drainQueueFor(server)
        pullFromServer(server, progressDao.observeAll().first(), openBookId, onOffer)
    }

    private suspend fun pullFromServer(
        server: SyncServer,
        locals: List<ReadingProgress>,
        openBookId: String?,
        onOffer: (RemoteProgressOffer) -> Unit,
    ) {
        val runner = runnerFor(server)
        var failure: IOException? = null
        for (local in locals) {
            if (failure != null) break
            try {
                val pulled = runner.pull(server, local.toSync())
                if (pulled != null) {
                    if (openBookId == local.bookId) {
                        onOffer(RemoteProgressOffer(local.bookId, pulled.pageIndex, server.id, server.baseUrl))
                    } else {
                        adopt(local, pulled)
                    }
                }
            } catch (e: IOException) {
                failure = e
            }
        }
    }

    suspend fun drainQueue() {
        val now = System.currentTimeMillis()
        val byServer = queue.due(now).groupBy { it.serverId }
        for ((serverId, entries) in byServer) {
            val server = servers.current().firstOrNull { it.id == serverId }?.toSyncServer() ?: continue
            drainEntries(server, entries, now)
        }
    }

    private suspend fun drainQueueFor(server: SyncServer) {
        val now = System.currentTimeMillis()
        drainEntries(server, queue.due(now).filter { it.serverId == server.id }, now)
    }

    private suspend fun drainEntries(server: SyncServer, entries: List<PendingPush>, now: Long) {
        val runner = runnerFor(server)
        for (entry in entries) {
            try {
                runner.sync(server, entry.toSync())
                queue.remove(server.id, entry.bookId)
            } catch (e: IOException) {
                queue.recordFailure(entry, now, e.message)
            }
        }
    }

    /** Adopts a pulled position with the remote's timestamp, so the next compare ties. */
    private suspend fun adopt(local: ReadingProgress, pulled: SyncProgress) {
        progressDao.upsert(local.copy(pageIndex = pulled.pageIndex, updatedAt = pulled.updatedAt))
    }

    private fun runnerFor(server: SyncServer): ServerSync =
        if (server.kind == ServerKind.KOMGA) komga else kavita

    /**
     * Sync covers Komga/Kavita records only; file servers (SMB/FTP) never map and are
     * skipped by every pass below, never guessed.
     */
    private fun RemoteServer.toSyncServer(): SyncServer? = when (this) {
        is KomgaServer -> SyncServer(id, ServerKind.KOMGA, baseUrl, allowCleartext, username, usesApiKey)
        is KavitaServer -> SyncServer(id, ServerKind.KAVITA, baseUrl, allowCleartext, username, usesApiKey)
        else -> null
    }
}

private fun ReadingProgress.toSync(): SyncProgress =
    SyncProgress(bookId = bookId, pageIndex = pageIndex, pageCount = pageCount, updatedAt = updatedAt)

private fun SyncProgress.toProgress(): ReadingProgress =
    ReadingProgress(bookId = bookId, pageIndex = pageIndex, pageCount = pageCount, updatedAt = updatedAt)

private fun SyncProgress.toPending(serverId: String): PendingPush =
    PendingPush(serverId, bookId, pageIndex, pageCount, updatedAt)

private fun PendingPush.toSync(): SyncProgress =
    SyncProgress(bookId = bookId, pageIndex = pageIndex, pageCount = pageCount, updatedAt = updatedAt)
