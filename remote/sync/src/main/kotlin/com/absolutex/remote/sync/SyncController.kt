package com.absolutex.remote.sync

import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Position pulled from a server, for the reader to adopt before the first page is shown.
 * Null means nothing newer exists anywhere — the reader keeps its own position.
 */
data class PulledPosition(val pageIndex: Int)

/** One server kind's compare-then-push and pull, behind the controller's triggers. */
interface ServerSync {
    /** Full compare-then-push; throws on transport failure (the caller queues). */
    suspend fun sync(server: SyncServer, local: SyncProgress)

    /** Adoptable remote position, or null when nothing newer exists. Never throws for that. */
    suspend fun pull(server: SyncServer, local: SyncProgress): SyncProgress?
}

/**
 * Sync triggers for the reader and app shells to call (see the TODOs below). All work runs
 * on Dispatchers.IO inside these suspending calls, so the reader never blocks on the network
 * and nothing here needs its own scope: retries are driven by triggers re-firing, and the
 * persisted queue ([SyncQueue]) survives process death between them. That is the measured
 * case against WorkManager (see the wiring PR): triggers already run, work is seconds of
 * network, nothing needs scheduled, exact or deadline execution — so WorkManager would add
 * scheduler weight for no capability.
 *
 * - Push (book close, page settle): compare-then-push per server; failures enqueue for
 *   bounded retry. Page settle reuses the reader's own debounce — the lead calls
 *   [onPageSettled] from the already-debounced write path, so there is no second debounce
 *   here by design.
 * - Pull (app start, book open, manual): newer remote positions are adopted with the
 *   remote's own timestamp, so the next comparison ties instead of flip-flopping.
 *
 * TODO(lead): wire ReaderViewModel — after `bookId` is known in `open()`, call
 *   `onBookOpened(bookId)` and prefer the returned page over `progressDao` when non-null
 *   (before the first frame); in `onPageChanged`'s debounced write, call
 *   `onPageSettled(bookId)` after the upsert; in `onCleared`'s flush, call
 *   `onBookClosed(bookId)`.
 * TODO(library): wire app start — in `AbsolutexApp.onCreate` (or the first MainActivity
 *   composition), inject this controller and call `onAppStart()` in a scope.
 */
@Singleton
class SyncController @Inject constructor(
    private val progressDao: ProgressDao,
    private val servers: SyncServers,
    secrets: SyncSecrets,
    private val queue: SyncQueue,
    http: HttpCall,
) {
    private val komga = KomgaSync(http, secrets, progressDao)
    private val kavita = KavitaSync(http, secrets, progressDao)

    /** App start / manual: flush the outbox, then adopt anything newer for known books. */
    suspend fun onAppStart() = withContext(Dispatchers.IO) {
        drainQueue()
        pullKnownBooks()
    }

    suspend fun onManualSync() = withContext(Dispatchers.IO) {
        drainQueue()
        pullKnownBooks()
    }

    /** Book open: pull only. Returns a position to adopt pre-first-paint, or null. */
    suspend fun onBookOpened(bookId: String): PulledPosition? = withContext(Dispatchers.IO) {
        val local = progressDao.get(bookId)?.toSync()
        if (local == null) return@withContext null
        for (server in servers.current()) {
            val pulled = runnerFor(server).pull(server, local)
            if (pulled != null) return@withContext PulledPosition(pulled.pageIndex)
        }
        null
    }

    /** Book close / page settle: compare-then-push per server, enqueue on failure. */
    suspend fun onBookClosed(bookId: String) = withContext(Dispatchers.IO) {
        pushBook(bookId)
    }

    suspend fun onPageSettled(bookId: String) = withContext(Dispatchers.IO) {
        pushBook(bookId)
    }

    private suspend fun pushBook(bookId: String) {
        val local = progressDao.get(bookId)?.toSync() ?: return
        var queued = false
        for (server in servers.current()) {
            try {
                runnerFor(server).sync(server, local)
            } catch (e: IOException) {
                queue.enqueue(local.toPending(server.id), e.message)
                queued = true
            }
        }
        if (queued) drainQueue()
    }

    private suspend fun drainQueue() {
        val now = System.currentTimeMillis()
        val byServer = queue.due(now).groupBy { it.serverId }
        for ((serverId, entries) in byServer) {
            val server = servers.current().firstOrNull { it.id == serverId } ?: continue
            val runner = runnerFor(server)
            for (entry in entries) {
                try {
                    runner.sync(server, entry.toSync())
                    queue.remove(serverId, entry.bookId)
                } catch (e: IOException) {
                    queue.recordFailure(entry, now, e.message)
                }
            }
        }
    }

    private suspend fun pullKnownBooks() {
        val locals = progressDao.observeAll().first()
        for (server in servers.current()) {
            val runner = runnerFor(server)
            var failure: IOException? = null
            for (local in locals) {
                if (failure != null) break
                try {
                    runner.pull(server, local.toSync())?.let { adopt(local, it) }
                } catch (e: IOException) {
                    failure = e
                }
            }
        }
    }

    /** Adopts a pulled position with the remote's timestamp, so the next compare ties. */
    private suspend fun adopt(local: ReadingProgress, pulled: SyncProgress) {
        progressDao.upsert(local.copy(pageIndex = pulled.pageIndex, updatedAt = pulled.updatedAt))
    }

    private fun runnerFor(server: SyncServer): ServerSync =
        if (server.kind == ServerKind.KOMGA) komga else kavita
}

private fun ReadingProgress.toSync(): SyncProgress =
    SyncProgress(bookId = bookId, pageIndex = pageIndex, pageCount = pageCount, updatedAt = updatedAt)

private fun SyncProgress.toPending(serverId: String): PendingPush =
    PendingPush(serverId, bookId, pageIndex, pageCount, updatedAt)

private fun PendingPush.toSync(): SyncProgress =
    SyncProgress(bookId = bookId, pageIndex = pageIndex, pageCount = pageCount, updatedAt = updatedAt)
