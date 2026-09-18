package com.absolutex.remote.sync

import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.IOException

/**
 * The sync mechanics behind [SyncController]'s triggers: pushing, pulling, adopting and the
 * retry outbox. Split out so the controller stays a thin trigger surface; everything here
 * runs on the caller's dispatcher (the controller confines it to Dispatchers.IO), and every
 * network failure surfaces as IOException for the queue. Open-book tracking and offers live
 * on the controller — the runner only takes them as arguments.
 *
 * Failure policy by status (see [HttpStatusException]): 401/403 stop the server — retried
 * auth is an account lockout — and surface on [stoppedServers] for a "sign in again" UI;
 * any success unstops. 404 on a mapped book invalidates the mapping (skip, never queue:
 * pushing into a deleted book loops). 5xx and transport failures keep the bounded backoff.
 */
class SyncRunner(
    private val progressDao: ProgressDao,
    private val servers: RemoteServers,
    private val queue: SyncQueue,
    private val komga: ServerSync,
    private val kavita: ServerSync,
) {
    private val _stoppedServers = MutableStateFlow<Set<String>>(emptySet())

    /** Server ids whose credentials were refused; the UI offers "sign in again" for these. */
    val stoppedServers: StateFlow<Set<String>> = _stoppedServers.asStateFlow()

    suspend fun pushBook(bookId: String, attemptStopped: Boolean = false) {
        val local = progressDao.get(bookId)?.toSync() ?: return
        var queued = false
        for (server in servers.current().mapNotNull { it.toSyncServer() }) {
            if (!attemptStopped && server.id in _stoppedServers.value) continue
            try {
                runnerFor(server).sync(server, local)
                _stoppedServers.update { it - server.id }
            } catch (e: HttpStatusException) {
                if (isAuthFailure(e)) {
                    _stoppedServers.update { it + server.id }
                } else if (e.code != HTTP_NOT_FOUND) {
                    queue.enqueue(local.toPending(server.id), e.message)
                    queued = true
                }
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
        attemptStopped: Boolean = false,
        onOffer: (RemoteProgressOffer) -> Unit,
    ): SyncProgress? {
        val local = progressDao.get(bookId)?.toSync() ?: return null
        val targets = servers.current()
            .mapNotNull { it.toSyncServer() }
            .filter { attemptStopped || it.id !in _stoppedServers.value }
        for (server in targets) {
            val pulled = pullOrNull(server, local) ?: continue
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

    /**
     * One server's pull with the status policy folded in: auth failures stop the server,
     * invalid mappings read as nothing-newer, and anything else propagates like before.
     * Any clean answer — newer position or not — proves the credentials work and unstops.
     */
    private suspend fun pullOrNull(server: SyncServer, local: SyncProgress): SyncProgress? {
        try {
            val pulled = runnerFor(server).pull(server, local)
            _stoppedServers.update { it - server.id }
            return pulled
        } catch (e: HttpStatusException) {
            if (isAuthFailure(e)) {
                _stoppedServers.update { it + server.id }
            } else if (e.code != HTTP_NOT_FOUND) {
                throw e
            }
        }
        return null
    }

    suspend fun pullKnownBooks(openBookId: String?, onOffer: (RemoteProgressOffer) -> Unit) {
        // Explicit passes always attempt: a fixed password recovers here (success unstops),
        // and a still-dead one re-stops without queueing. Within one server, the first
        // auth failure stops the rest of its books — no point hammering a dead credential.
        val locals = progressDao.observeAll().first()
        for (server in servers.current().mapNotNull { it.toSyncServer() }) {
            pullFromServer(server, locals, openBookId, onOffer)
        }
    }

    /** Folder browse / refresh for one server: its outbox, then a pull through it. */
    suspend fun syncServer(serverId: String, openBookId: String?, onOffer: (RemoteProgressOffer) -> Unit) {
        val server = servers.current().firstOrNull { it.id == serverId }?.toSyncServer() ?: return
        val now = System.currentTimeMillis()
        drainEntries(server, queue.due(now).filter { it.serverId == server.id }, now)
        pullFromServer(server, progressDao.observeAll().first(), openBookId, onOffer)
    }

    private suspend fun pullFromServer(
        server: SyncServer,
        locals: List<ReadingProgress>,
        openBookId: String?,
        onOffer: (RemoteProgressOffer) -> Unit,
    ) {
        var failure: IOException? = null
        var halted = false
        for (local in locals) {
            if (failure != null || halted) break
            try {
                val pulled = runnerFor(server).pull(server, local.toSync())
                _stoppedServers.update { it - server.id }
                if (pulled != null) {
                    if (openBookId == local.bookId) {
                        onOffer(RemoteProgressOffer(local.bookId, pulled.pageIndex, server.id, server.baseUrl))
                    } else {
                        adopt(local, pulled)
                    }
                }
            } catch (e: HttpStatusException) {
                if (isAuthFailure(e)) {
                    _stoppedServers.update { it + server.id }
                    halted = true
                } else if (e.code != HTTP_NOT_FOUND) {
                    failure = e
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

    private suspend fun drainEntries(server: SyncServer, entries: List<PendingPush>, now: Long) {
        val runner = runnerFor(server)
        for (entry in entries) {
            try {
                runner.sync(server, entry.toSync())
                queue.remove(server.id, entry.bookId)
                _stoppedServers.update { it - server.id }
            } catch (e: HttpStatusException) {
                // Auth failures and invalid mappings can never succeed on retry: drop the
                // entry instead of re-queueing it, and stop (auth) or skip (mapping) the server.
                if (isAuthFailure(e)) {
                    _stoppedServers.update { it + server.id }
                    queue.remove(server.id, entry.bookId)
                } else if (e.code == HTTP_NOT_FOUND) {
                    queue.remove(server.id, entry.bookId)
                } else {
                    queue.recordFailure(entry, now, e.message)
                }
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

/** Auth refusals stop a server; every other status keeps its own path (retry or skip). */
internal fun isAuthFailure(e: HttpStatusException): Boolean =
    e.code == HTTP_UNAUTHORIZED || e.code == HTTP_FORBIDDEN

private fun ReadingProgress.toSync(): SyncProgress =
    SyncProgress(bookId = bookId, pageIndex = pageIndex, pageCount = pageCount, updatedAt = updatedAt)

private fun SyncProgress.toProgress(): ReadingProgress =
    ReadingProgress(bookId = bookId, pageIndex = pageIndex, pageCount = pageCount, updatedAt = updatedAt)

private fun SyncProgress.toPending(serverId: String): PendingPush =
    PendingPush(serverId, bookId, pageIndex, pageCount, updatedAt)

private fun PendingPush.toSync(): SyncProgress =
    SyncProgress(bookId = bookId, pageIndex = pageIndex, pageCount = pageCount, updatedAt = updatedAt)
