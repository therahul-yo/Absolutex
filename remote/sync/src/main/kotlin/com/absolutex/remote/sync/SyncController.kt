package com.absolutex.remote.sync

import com.absolutex.core.data.ProgressDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
 *   remote's own timestamp, so the next comparison ties instead of flip-flopping — except
 *   for the already-open book, whose pulls surface on [progressOffers] instead of moving
 *   the page (see below).
 * - App background: the app shell calls [onAppBackgrounded] from its own lifecycle observer
 *   (lifecycle-process is deliberately not a dependency — see the wiring PR's Decisions).
 *
 * TODO(lead): wire ReaderViewModel — after `bookId` is known in `open()`, call
 *   `onBookOpened(bookId)` and prefer the returned page over `progressDao` when non-null
 *   (before the first frame); in `onPageChanged`'s debounced write, call
 *   `onPageSettled(bookId)` after the upsert; in `onCleared`'s flush, call
 *   `onBookClosed(bookId)`.
 * TODO(lead): offer "Continue at page N from <server>" — collect `progressOffers` in the
 *   reader chrome and show a non-moving snackbar/dialog for non-null values (adopting only
 *   on tap, then call `consumeOffer()`); a pull landing after the first page must never
 *   move the page on its own.
 * TODO(library): wire app start and background — in `AbsolutexApp.onCreate` (or the first
 *   MainActivity composition), inject this controller and call `onAppStart()` in a scope;
 *   observe the app lifecycle there and call `onAppBackgrounded()` when it leaves the
 *   foreground while reading.
 */
@Singleton
class SyncController @Inject constructor(
    progressDao: ProgressDao,
    servers: RemoteServers,
    secrets: SyncSecrets,
    queue: SyncQueue,
    http: HttpCall,
    clock: ServerClock,
) {
    private val runner = SyncRunner(
        progressDao,
        servers,
        queue,
        KomgaSync(http, secrets, progressDao, clock),
        KavitaSync(http, secrets, progressDao, clock),
    )

    private val _offers = MutableStateFlow<RemoteProgressOffer?>(null)

    /** Newer remote positions for the already-open book; the reader offers, never applies. */
    val progressOffers: StateFlow<RemoteProgressOffer?> = _offers.asStateFlow()

    /** Book the reader currently holds open, if any — pulls for it become offers. */
    private var openBookId: String? = null

    /** App start / manual: flush the outbox, then adopt anything newer for known books. */
    suspend fun onAppStart() = withContext(Dispatchers.IO) {
        runner.drainQueue()
        runner.pullKnownBooks(openBookId) { _offers.value = it }
    }

    suspend fun onManualSync() = withContext(Dispatchers.IO) {
        runner.drainQueue()
        runner.pullKnownBooks(openBookId) { _offers.value = it }
    }

    /**
     * App to background while reading: push the open book first (the reader may not be
     * torn down, so its close flush may never run), then the manual pass.
     */
    suspend fun onAppBackgrounded(bookId: String?) = withContext(Dispatchers.IO) {
        if (bookId != null) {
            runner.pushBook(bookId)
        }
        runner.drainQueue()
        runner.pullKnownBooks(openBookId) { _offers.value = it }
    }

    /** Book open: pull only. Returns a position to adopt pre-first-paint, or null. */
    suspend fun onBookOpened(bookId: String): PulledPosition? = withContext(Dispatchers.IO) {
        openBookId = bookId
        val pulled = runner.pullBook(bookId, offerWhenOpen = false, openBookId) { _offers.value = it }
        if (pulled != null) PulledPosition(pulled.pageIndex) else null
    }

    /** Book close / page settle: compare-then-push per server, enqueue on failure. */
    suspend fun onBookClosed(bookId: String) = withContext(Dispatchers.IO) {
        runner.pushBook(bookId)
        if (openBookId == bookId) {
            openBookId = null
        }
        if (_offers.value?.bookId == bookId) {
            _offers.value = null
        }
    }

    suspend fun onPageSettled(bookId: String) = withContext(Dispatchers.IO) {
        runner.pushBook(bookId)
    }

    /** Folder browse / refresh for one server: flush its outbox, then pull through it. */
    suspend fun syncNow(serverId: String) = withContext(Dispatchers.IO) {
        runner.syncServer(serverId, openBookId) { _offers.value = it }
    }

    /** Pushes (and pulls) exactly these books — the browse/refresh trigger for a folder. */
    suspend fun syncBooks(bookIds: List<String>) = withContext(Dispatchers.IO) {
        for (bookId in bookIds) {
            runner.pushBook(bookId)
            runner.pullBook(bookId, offerWhenOpen = true, openBookId) { _offers.value = it }
        }
    }

    /** Clears the current offer after the reader has shown or adopted it. */
    fun consumeOffer() {
        _offers.value = null
    }
}
