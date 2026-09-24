package com.absolutex.feature.reader

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Trace
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.TotalRamBytes
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.data.settings.ReaderPrefsSource
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.AppPrefsSource
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.data.settings.RenderingPrefsSource
import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.core.decode.MemoryBudget
import com.absolutex.core.decode.PageImage
import com.absolutex.core.thumbnails.ThumbRequest
import com.absolutex.core.thumbnails.ThumbnailPipeline
import com.absolutex.core.decode.TileCache
import com.absolutex.model.BookIdentity
import com.absolutex.model.Toc
import com.absolutex.model.TocEntry
import com.absolutex.remote.core.REMOTE_URI_SCHEME
import com.absolutex.remote.core.RemoteBookOpener
import com.absolutex.remote.core.RemoteOpenResult
import com.absolutex.source.ComicSource
import com.absolutex.source.pdf.PdfDocument
import com.absolutex.source.pdf.PdfPasswordException
import java.io.Closeable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val TAG = "Reader"

/** A page is never four times taller than it is wide; the thumbnail fits inside that box. */
private const val THUMB_HEIGHT_LIMIT = 4

data class ReaderUiState(
    val loading: Boolean = false,
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    /** Scopes cached tiles to this book. Empty until a book is open. */
    val bookId: String = "",
    val error: String? = null,
    /** Payload recovery report, never used as the pager/progress count. */
    val recoveryNotice: String? = null,
    /**
     * An encrypted PDF is waiting for its password. Set alongside [error] (the same generic
     * string), so dismissing the prompt leaves the ordinary failure behind it. The password
     * itself is never held here: it travels as an argument to [ReaderViewModel.open] and lives
     * otherwise only in the dialog's own text field, which composition drops on dismiss.
     */
    val passwordRequired: Boolean = false,
    /** The prompt is a retry: a password was offered and PDFium refused it. */
    val passwordIncorrect: Boolean = false,
    /** A reflowable text EPUB: the screen shows the text reader instead of pages. */
    val textEpub: Boolean = false,
)

@HiltViewModel
class ReaderViewModel internal constructor(
    @ApplicationContext private val context: Context,
    private val progressDao: ProgressDao,
    @TotalRamBytes private val totalRamBytes: Long,
    prefs: ReaderPrefsSource,
    rendering: RenderingPrefsSource,
    private val appPrefs: AppPrefsSource,
    private val bookOpener: BookOpener,
    private val remoteBookOpener: RemoteBookOpener,
) : ViewModel() {

    /**
     * The constructor Hilt uses; only the seven Hilt-known params above are real dependencies.
     * Needs its own internal constructor rather than a Kotlin default argument for [bookOpener]
     * because Dagger cannot see Kotlin defaults on an `@Inject` constructor (see
     * LibraryRepository for the same pattern and the same reason). Tests use the internal
     * constructor to supply a fake [BookOpener] and [RemoteBookOpener].
     */
    @Inject constructor(
        @ApplicationContext context: Context,
        progressDao: ProgressDao,
        @TotalRamBytes totalRamBytes: Long,
        prefs: ReaderPrefsSource,
        rendering: RenderingPrefsSource,
        appPrefs: AppPrefsSource,
        remoteBookOpener: RemoteBookOpener,
    ) : this(
        context, progressDao, totalRamBytes, prefs, rendering, appPrefs, ContextBookOpener(context), remoteBookOpener,
    )

    /**
     * Reading flow and fit mode, live. Eager so the value is usually in hand before the first page:
     * the archive open that gates rendering takes longer than the first DataStore read.
     */
    val readerPrefs: StateFlow<ReaderPrefs> =
        prefs.readerPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

    /** Draw-time rendering behaviour (§5.4, Rendering group) — colour, upscaler, auto background. */
    val renderingPrefs: StateFlow<RenderingPrefs> =
        rendering.renderingPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, RenderingPrefs())

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    // Pages whose bytes failed to decode. The UI shows a generic string for these —
    // never the raw entry name — while detail goes to logcat.
    /** The open book's contents, empty when it has none (§5.2). */
    private val _toc = MutableStateFlow<List<TocEntry>>(emptyList())
    val toc: StateFlow<List<TocEntry>> = _toc.asStateFlow()

    private val _failedPages = MutableStateFlow<Set<Int>>(emptySet())
    val failedPages: StateFlow<Set<Int>> = _failedPages.asStateFlow()

    val tileCache = TileCache(MemoryBudget.defaultCacheBytes(totalRamBytes))

    /**
     * Applies cache-size changes mid-book. The open() coroutine applies the setting once at
     * open; this collector keeps it live: a user who raises the cache while a book is open
     * gets the bigger budget without reopening. Resize clamps and trims on shrink, so a
     * mid-read downsize evicts correctly. distinctUntilChanged: resize is idempotent but
     * not free, and every app-pref write re-emits the whole AppPrefs object.
     */
    private var cacheSizeJob: Job? = null

    /** The open book: a [ComicSource] whose pages are encoded images, or a [PdfDocument]. */
    private var source: Closeable? = null

    /**
     * Whether [source] is a remote book, which decides how it may be closed.
     *
     * A local close is in-process — a recycled bitmap, a native PDF handle. A remote one is a
     * session teardown round trip: SMB logs off, FTP sends QUIT bounded only by its 30 s socket
     * timeout. The flag is set where the book is opened rather than sniffed from the type, so
     * the close path cannot be wrong about a source it did not open.
     */
    private var sourceIsRemote = false

    /**
     * Page thumbnails for the chrome's strip, on their own caches and dispatcher so a strip scroll
     * can never starve the page being read. One per book: its disk entries are keyed by book.
     * Built lazily (see [ThumbPipelineHolder]), not in [open]: a book's first page never needs one.
     */
    private val thumbs = ThumbPipelineHolder(context.cacheDir)
    private var bookId: String = ""
    /** The Uri currently open, for the same-book check. Distinct from [bookId], the book's identity. */
    private var openedUri: String = ""
    /** The Uri an open is in flight for, so a repeat request for it joins rather than restarts. */
    private var openingUri: String = ""
    // Thread-safe for get-on-Main + put-on-decode-pool; bounded to a sliding window
    // around the current page (see evictFarPages). ConcurrentHashMap needs the manual
    // bound because it has no access-order eviction of its own. A plain HashMap here was
    // a data race: reads happen on Main, writes on the decode pool.
    private val pageImages = ConcurrentHashMap<Int, PageImage>()

    /**
     * Base layers, finished or still decoding, keyed by page and size. A second request for the same
     * base awaits the first instead of starting another decode. On the reference phone the resumed
     * page was decoded twice at once on every open, identical page, size and viewport, which cost
     * ~150 ms of the tap-to-first-page budget; swiping back to a page also re-decoded its base.
     * Native decodes cannot be cancelled, so a duplicate is paid for in full.
     */
    private val bases = ConcurrentHashMap<BaseKey, Deferred<Bitmap?>>()

    private val openGeneration = AtomicInteger(0)
    private var openJob: Job? = null
    // Coalesces fling bursts into one Room write: cancel-and-relaunch around the upsert.
    private var pendingProgressWrite: Job? = null
    /** Last progress handed to the debounce, so onCleared can flush what it still owes. */
    private var pendingProgress: ReadingProgress? = null
    /**
     * Page the pager has settled on. Deliberately NOT in [ReaderUiState]: only the initial
     * pager position reads currentPage, so writing it per settle recomposed ReaderScreen,
     * Pages, the pager and every composed page for a value nothing looked at again.
     */
    private var settledPage: Int = 0

    /** The book page being read: where a reader rebuilt for a new page layout reopens. */
    val readingPage: Int get() = settledPage

    companion object {
        /** Resident decoded pages. ~12 covers viewport + prefetch without ballooning native heap. */
        const val MAX_RESIDENT_PAGES = 12

        /** Base layers kept around the settled page: it and two either side, ~9 MB each here. */
        const val BASE_WINDOW = 2

        /** A fast fling settles dozens of pages; only the landing page should hit disk. */
        const val PROGRESS_DEBOUNCE_MS = 300L

        private const val BYTES_PER_MIB = 1024L * 1024
    }

    /**
     * Opens [uri], joining an in-flight open of the same Uri rather than restarting it.
     *
     * [password] is the encrypted-PDF retry: the prompt submits back through this same function.
     * Remote books never take the password path — [RemoteOpenResult.Ready] carries a ComicSource,
     * and a PDF is not one — so it reaches only the local open inside [openAttempt].
     */
    fun open(uri: Uri, password: String? = null) {
        // Already open, and nothing else is in flight to contradict it: a genuine no-op. The
        // "nothing else in flight" half matters because a request to reopen the current book can
        // arrive while a DIFFERENT book's open is still running — rapid back-and-forth on the one
        // activity-scoped instance every book shares. openedUri/source are only written when an
        // open finishes, so both still describe the current book for that whole in-flight window;
        // without this check, reopening it here returned early having done nothing, leaving the
        // other book's job as the only thing still running and its result the one that would win.
        if (openedUri == uri.toString() && source != null && openJob?.isActive != true) return
        // The activity starts opening a launch Uri in onCreate, before the reader composes, and the
        // reader then asks for the same Uri. That second call must join the open in flight, not
        // cancel and restart it — a restart would throw away the head start it exists to give.
        if (openingUri == uri.toString() && openJob?.isActive == true) return
        openingUri = uri.toString()
        // Cancel any in-flight open so a rapid book switch cannot land stale state.
        openJob?.cancel()
        val generation = openGeneration.incrementAndGet()
        // Drop the previous book's decoded state now so tiles/pages cannot alias
        // across books while the new archive extracts.
        tileCache.clear()
        pageImages.values.forEach { runCatching { it.close() } }
        pageImages.clear()
        bases.clear()
        _ui.value = ReaderUiState(loading = true)
        openJob = viewModelScope.launch {
            // Apply the user's cache-size setting before the book opens, and keep applying it
            // for the rest of this scope (see cacheSizeJob's own KDoc for why a mid-book change
            // still has to land).
            cacheSizeJob?.cancel()
            // viewModelScope as the receiver, not this launch: the collector never returns, and
            // as a child of the open it would keep the open job active forever — after which a
            // same-uri resubmission (the error screen's Retry, a password submit) mistakes
            // itself for a join of an open still in flight and is silently dropped.
            cacheSizeJob = viewModelScope.applyCacheSizePref(appPrefs, tileCache)

            when (val attempt = openAttempt(context, bookOpener, remoteBookOpener, uri, password)) {
                is OpenAttempt.Opened -> {
                    val (source0, identity, remote) = attempt
                    val count = (source0 as? PdfDocument)?.pageCount
                        ?: (source0 as ComicSource).pages.size
                    if (generation != openGeneration.get()) {
                        closeSource(source0, remote)
                        return@launch
                    }
                    // Close the old source only now, immediately before replacing, so in-flight
                    // page decodes against it fail cleanly instead of racing a premature close.
                    val old = source
                    val oldWasRemote = sourceIsRemote
                    source = source0
                    sourceIsRemote = remote
                    // Closed now, but not rebuilt until first use (see ThumbPipelineHolder): its
                    // disk-journal reconciliation is a real cost that must not land before the
                    // state below, which gates the first page.
                    thumbs.reset()
                    openedUri = uri.toString()
                    bookId = identity
                    closeSource(old, oldWasRemote)
                    val resume = progressDao.get(bookId)?.pageIndex ?: 0
                    // The previous book's settled page would otherwise stand in until the pager settles.
                    settledPage = resume.coerceIn(0, (count - 1).coerceAtLeast(0))
                    _ui.value = ReaderUiState(
                        loading = false,
                        title = bookOpener.titleOf(uri),
                        pageCount = count,
                        bookId = bookId,
                        currentPage = settledPage,
                        recoveryNotice = recoveryNoticeFor(context, source0),
                    )
                    // After the state that gates the first page: contents are chrome, and a PDF
                    // outline is a JNI call whose cost must not land in the tap-to-first-page budget.
                    _toc.value = withContext(DecodeDispatchers.extract) { contentsOf(source0) }
                }
                is OpenAttempt.Show -> if (generation == openGeneration.get()) _ui.value = attempt.state
            }
        }
    }

    /**
     * Decoded page, cached. Called off the main thread by the reader.
     *
     * Staged across two pools: archive I/O on [DecodeDispatchers.extract], pixel decode on
     * [DecodeDispatchers.decode]. Only the byte[] crosses between them.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend fun pageImage(index: Int): PageImage? {
        val src = source ?: return null
        pageImages[index]?.let { return it }

        val decoded = try {
            if (src is PdfDocument) {
                withContext(DecodeDispatchers.decode) { PdfPageImage.open(src, index) }
            } else {
                val bytes = withContext(DecodeDispatchers.extract) { (src as ComicSource).openPage(index).readBytes() }
                withContext(DecodeDispatchers.decode) { PageImage.from(bytes) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return failPage(index, e)
        } catch (e: RuntimeException) {
            return failPage(index, e)
        }

        // Never cache poison: a decode with no dimensions is an unreadable page, not a page.
        if (decoded.width <= 0 || decoded.height <= 0) {
            runCatching { decoded.close() }
            return failPage(index, IOException("decoded page $index has no dimensions"))
        }

        // Another coroutine may have decoded the same page concurrently; keep the winner and
        // close the loser rather than leaking its BitmapRegionDecoder.
        val prev = pageImages.putIfAbsent(index, decoded)
        return if (prev != null) {
            runCatching { decoded.close() }
            prev
        } else {
            evictFarPages(pageImages, settledPage)
            decoded
        }
    }

    /** Clears one cached page and its failure mark so the UI retry forces a fresh decode. */
    fun invalidatePage(index: Int) {
        pageImages.remove(index)?.let { runCatching { it.close() } }
        bases.keys.removeIf { it.page == index }
        _failedPages.value -= index
    }

    /**
     * The page's base layer at [width]x[height], decoded once however many callers ask. The decode
     * runs in the ViewModel's scope, so a caller leaving composition does not waste a decode someone
     * else is about to await. A failed decode is not remembered, so a retry decodes again.
     */
    suspend fun baseLayer(index: Int, image: PageImage, width: Int, height: Int): Bitmap? {
        val key = BaseKey(bookId, index, width, height)
        val decode = bases.computeIfAbsent(key) {
            viewModelScope.async(DecodeDispatchers.decode, start = CoroutineStart.LAZY) {
                Trace.beginSection("absx.base p=$index ${width}x$height")
                try {
                    runCatching { image.decodeBase(width, height) }.getOrNull()
                } finally {
                    Trace.endSection()
                }
            }
        }
        val bitmap = decode.await()
        if (bitmap == null) {
            bases.remove(key, decode)
        } else {
            // Keep only the settled page's neighbourhood, and only this size for the page just
            // decoded (see baseKeysToKeep): BaseKey carries width and height, and a page resized
            // more than once while still in the window — a multi-window drag, a foldable
            // fold/unfold, a rotation — must not keep one ~9 MB bitmap per size it was ever
            // measured at.
            bases.keys.retainAll(baseKeysToKeep(bases.keys, settledPage, BASE_WINDOW, key))
        }
        return bitmap
    }

    /**
     * Dismisses the encrypted-PDF prompt, keeping the generic failure it was shown over.
     *
     * The guard is the whole point: a submit already in flight has cleared the prompt for a
     * loading state, and clobbering that with an error would lie about an open still running.
     * A member (rather than top-level like [openAttempt]) because only the class may write its
     * own state — the room for it comes from [evictFarPages] moving the other way.
     */
    fun cancelPasswordPrompt() {
        if (_ui.value.passwordRequired) {
            _ui.value = ReaderUiState(
                loading = false,
                error = context.getString(R.string.reader_open_failed),
            )
        }
    }

    /**
     * Records an unreadable page so the reader can say so instead of spinning forever (§2's
     * "N of M readable"). CancellationException is rethrown by the callers rather than landing
     * here: a cancelled prefetch is not a corrupt page, and swallowing it would also break
     * structured concurrency.
     */
    private fun failPage(index: Int, cause: Throwable): PageImage? {
        Log.e(TAG, "page $index unreadable", cause)
        _failedPages.value += index
        return null
    }

    fun onPageChanged(index: Int) {
        if (index == settledPage) return
        settledPage = index
        val id = bookId
        // Capture before launching: a book switch mid-write must not persist the new
        // book's index against the old count (or vice versa).
        val count = _ui.value.pageCount
        if (id.isEmpty()) return
        pendingProgressWrite?.cancel()
        val progress = ReadingProgress(
            bookId = id,
            pageIndex = index,
            pageCount = count,
            // Every row carries its own timestamp: §5.5 sync is last-write-wins.
            updatedAt = System.currentTimeMillis(),
        )
        pendingProgress = progress
        pendingProgressWrite = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            progressDao.upsert(progress)
            pendingProgress = null
        }
    }

    override fun onCleared() {
        // The debounced write lives in viewModelScope and dies with it, so a book closed
        // inside the debounce window would lose its last page. Flush it detached.
        pendingProgressWrite?.cancel()
        pendingProgress?.let { progress ->
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { progressDao.upsert(progress) }
        }
        openJob?.cancel()
        cacheSizeJob?.cancel()
        _toc.value = emptyList()
        pageImages.values.forEach { runCatching { it.close() } }
        pageImages.clear()
        bases.clear()
        tileCache.clear()
        thumbs.reset()
        // viewModelScope is already cancelled here and onCleared runs on Main, so a remote
        // close gets the same detached treatment the pending progress write above does.
        val closing = source
        if (sourceIsRemote) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { closing?.close() } }
        } else {
            runCatching { closing?.close() }
        }
        source = null
        sourceIsRemote = false
        super.onCleared()
    }

    /**
     * One page thumbnail, for the chrome's strip (§5.2).
     *
     * An archive goes through [ThumbnailPipeline], which caches to disk, so reopening a book does
     * not re-extract 45 pages. A PDF has no encoded bytes to cache, so PDFium renders the page
     * small, which is already cheap.
     */
    suspend fun thumbnail(index: Int, width: Int): Bitmap? {
        val src = source ?: return null
        return runCatching {
            if (src is PdfDocument) {
                withContext(DecodeDispatchers.decode) {
                    PdfPageImage.open(src, index).decodeBase(width, width * THUMB_HEIGHT_LIMIT)
                }
            } else {
                thumbs.get().load(src as ComicSource, ThumbRequest(bookId, index, ThumbRequest.snapWidth(width)))
            }
        }.getOrNull()
    }

    /**
     * Writes the page to Pictures/Absolutex (§5.2) and returns its Uri, or null if it could not be
     * written. An archive page is exported byte for byte: re-encoding a scan to export it would
     * lose quality for nothing. A PDF page has no bytes of its own, so it is rendered and encoded.
     */
    suspend fun exportPage(index: Int): Uri? {
        val src = source ?: return null
        val title = _ui.value.title
        return runCatching {
            if (src is PdfDocument) {
                // Its own dispatcher, not extract's (see DecodeDispatchers.export): a
                // full-resolution render (up to EXPORT_MAX_EDGE, already the bound export needs —
                // PageImage.fitInside never renders larger than that box) can run for hundreds of
                // milliseconds, and extract's pool is what archive prefetch needs free while a book
                // is open. The size bound was already sensible; what moved is where the render runs.
                withContext(DecodeDispatchers.export) {
                    val page = PdfPageImage.open(src, index)
                    context.exportPageBitmap(title, index, page.decodeBase(EXPORT_MAX_EDGE, EXPORT_MAX_EDGE))
                }
            } else {
                withContext(DecodeDispatchers.extract) {
                    val source1 = src as ComicSource
                    val bytes = source1.openPage(index).use { it.readBytes() }
                    context.exportPageBytes(title, index, source1.pages[index].entryName, bytes)
                }
            }
        }.onFailure { Log.e(TAG, "export failed", it) }.getOrNull()
    }

    /** Halves the tile budget on memory pressure; called from MainActivity's callbacks. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            tileCache.trimToSize(tileCache.maxBytes() / 2)
            // Under memory pressure only the settled page is worth keeping at all, and only at
            // one size: keeping every size it had ever been measured at (the same unbounded shape
            // baseLayer's own eviction used to have) would defeat the trim it's supposed to do.
            val keepSize = bases.keys.firstOrNull { it.page == settledPage }
            bases.keys.retainAll(setOfNotNull(keepSize))
        }
    }
}

/**
 * The user's cache-size choice in bytes, clamped to the user-facing bounds: the floor is
 * [AppPrefs.MIN_CACHE_MIB] (deliberately ~two flagship pages, see its KDoc), NOT
 * [MemoryBudget.FLOOR_BYTES], which bounds the RAM-derived default rather than a value
 * the user picked. The ceiling stays MemoryBudget's.
 */
private fun userCacheBytes(mib: Int): Long =
    (mib.toLong() * BYTES_PER_MIB).coerceIn(
        AppPrefs.MIN_CACHE_MIB.toLong() * BYTES_PER_MIB,
        MemoryBudget.CEILING_BYTES,
    )

private const val BYTES_PER_MIB = 1024L * 1024

/**
 * Applies the user's [appPrefs] cache-size setting to [tileCache] once, then launches (and
 * returns) a collector that keeps applying it for the rest of [this] scope's life, so a
 * mid-book change to the setting still resizes the live cache.
 *
 * Top-level, not a member of [ReaderViewModel]: the class is already at detekt's function-count
 * ceiling, and this needs nothing from it but its two parameters.
 */
private suspend fun CoroutineScope.applyCacheSizePref(appPrefs: AppPrefsSource, tileCache: TileCache): Job {
    // The floor is the user-facing one (AppPrefs.MIN_CACHE_MIB — deliberately ~two flagship
    // pages, see its KDoc), NOT MemoryBudget.FLOOR_BYTES, which bounds the RAM-derived default,
    // not a value the user picked. Ceiling stays MemoryBudget's — see userCacheBytes.
    tileCache.resize(userCacheBytes(appPrefs.currentAppPrefs().cacheSizeMiB))
    return launch {
        appPrefs.appPrefs
            .map { it.cacheSizeMiB }
            .distinctUntilChanged()
            .collect { mib -> tileCache.resize(userCacheBytes(mib)) }
    }
}

/**
 * Opens an `absolutex-remote://` [uri] through [opener] and reshapes the result to the same
 * (source, identity) pair [BookOpener.open] returns, so everything after the branch in
 * [ReaderViewModel.open] — generation check, close-the-superseded-source, identity, count —
 * needs no idea which path it came from. [RemoteOpenResult.Ready.identity] is already
 * `BookIdentity.of(displayName, sizeBytes)` ([CoreComicSource.open] builds it): sizeBytes is the
 * transport's own [RangeTransport.sizeBytes][com.absolutex.remote.core.RangeTransport], read
 * once to seek the ZIP central directory, so identity costs no extra round trip.
 *
 * A format streaming can't serve ([RemoteOpenResult.DownloadRequired]) becomes an IOException,
 * same generic error path as a transport failure — its reason names a container format, never a
 * host or path, so it is as safe to log as any other detail.
 *
 * Top-level, not a member of [ReaderViewModel]: the class is already at detekt's function-count
 * ceiling, and this needs nothing from it but [opener] and [uri].
 */
/**
 * The payload-recovery notice for a freshly opened source, or null when it reported none.
 *
 * Top-level, like [closeSource] below, and for the same reason: inlined at the call site the
 * safe cast and the null-chain cost a branch each in `open`, which detekt's cyclomatic limit has
 * no room for — and as a member it would put the class over its function limit. Both limits are
 * right; `open` is already the longest decision path in this file.
 */
private fun recoveryNoticeFor(context: Context, source: Closeable): String? =
    (source as? ComicSource)?.pageReadability?.let { context.recoveryNotice(it) }

/**
 * Closes a book source without ever blocking Main.
 *
 * A remote close is network I/O (see [sourceIsRemote]), so it goes to the pool the
 * transports already block on; a local close stays inline, because moving a PDF close to
 * another thread would race in-flight renders for no benefit. NonCancellable: a cancelled
 * or superseded open must still close what it opened.
 */
private suspend fun closeSource(handle: Closeable?, remote: Boolean) {
    if (handle == null) return
    if (remote) {
        withContext(NonCancellable + DecodeDispatchers.extract) { runCatching { handle.close() } }
    } else {
        runCatching { handle.close() }
    }
}

private suspend fun openRemote(opener: RemoteBookOpener, uri: Uri): Pair<Closeable, String> =
    when (val result = opener.open(uri.toString())) {
        is RemoteOpenResult.Ready -> result.source to result.identity
        is RemoteOpenResult.DownloadRequired -> throw IOException(result.reason)
    }

/**
 * What one attempt to open a book produced. Success carries the handle; every other outcome
 * already knows the exact UI state it shows, so [ReaderViewModel.open] stays a straight switch
 * rather than re-branching on exception types it just caught.
 */
private sealed interface OpenAttempt {
    data class Opened(val source: Closeable, val identity: String, val remote: Boolean) : OpenAttempt
    data class Show(val state: ReaderUiState) : OpenAttempt
}

/**
 * Tries the open behind [uri] and maps every outcome but success to its end state.
 *
 * Top-level, like [closeSource]: inlined, the three catches and the state-building would put
 * `open` over detekt's complexity and length limits, and as a member they would put the class
 * over its function limit. Both limits are right; `open` is already the longest decision path
 * in this file.
 *
 * Throwable on purpose: any failure to open a book must reach the user as one generic message,
 * and the detail is logged. Narrowing would let an unlisted failure crash instead.
 *
 * A wrong or absent PDF password is not that generic failure: it returns the prompt state, with
 * [ReaderUiState.passwordIncorrect] set exactly when a non-null password was refused. The
 * password itself is never stored — it arrives here as an argument and goes no further.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun openAttempt(
    context: Context,
    bookOpener: BookOpener,
    remoteBookOpener: RemoteBookOpener,
    uri: Uri,
    password: String?,
): OpenAttempt {
    val remote = uri.scheme == REMOTE_URI_SCHEME
    val generic = ReaderUiState(
        loading = false,
        error = context.getString(R.string.reader_open_failed),
    )
    return try {
        val opened = if (remote) {
            // No NonCancellable here: RemoteBookOpener.open is a real suspend fun, and
            // per its own KDoc ("the reader calls it from its open coroutine, same as
            // every other open path") a cancel is its own implementation's job to
            // survive without leaking whatever it opened — see RemoteModule in
            // :feature:remote for how the real binding does that. The reader only has
            // to trust the suspend contract, the same way it trusts BookOpener's.
            openRemote(remoteBookOpener, uri)
        } else {
            // NonCancellable: bookOpener.open() (openBook()/identityOf() in production —
            // see ContextBookOpener) is a blocking call with no suspension point of its
            // own, so cancelling this job cannot interrupt it — it always runs to
            // completion once started. Without NonCancellable, a cancel landing while it
            // was already finishing still made this resume with a CancellationException,
            // discarding the successfully-opened handle instead of returning it — leaking
            // its fd (and, for a PDF, its native handle) because it was then never
            // reachable by the generation check in finishOpen, which is what already closes
            // a result superseded by a newer, non-cancelling call to open().
            withContext(NonCancellable) { bookOpener.open(uri, password) }
        }
        val (source, identity) = opened
        OpenAttempt.Opened(source, identity, remote)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ReflowableEpubException) {
        Log.i(TAG, "text EPUB; opening in the text reader", e)
        OpenAttempt.Show(ReaderUiState(loading = false, textEpub = true, title = bookOpener.titleOf(uri)))
    } catch (e: PdfPasswordException) {
        // An encrypted PDF, with no password offered or the wrong one: prompt rather
        // than fail. The generic error rides along, so cancelling the prompt leaves
        // exactly the state a wrong-format book shows.
        Log.e(TAG, "open needs password", e)
        OpenAttempt.Show(generic.copy(passwordRequired = true, passwordIncorrect = password != null))
    } catch (t: Throwable) {
        // Generic UI string: t.message can embed the raw Uri or an archive entry name.
        // The detail goes to logcat only.
        Log.e(TAG, "open failed", t)
        OpenAttempt.Show(generic)
    }
}

/**
 * Keeps only a sliding window around the current page; closes evicted pages.
 *
 * Top-level, like [closeSource]: it needs nothing but its two parameters, and as a member it
 * would put `ReaderViewModel` over detekt's function-count ceiling — the room the password
 * prompt's [cancelPasswordPrompt][ReaderViewModel.cancelPasswordPrompt] member needed.
 */
private fun evictFarPages(pages: ConcurrentHashMap<Int, PageImage>, center: Int) {
    while (pages.size > ReaderViewModel.MAX_RESIDENT_PAGES) {
        val farthest = pages.keys.maxByOrNull { kotlin.math.abs(it - center) } ?: break
        pages.remove(farthest)?.let { runCatching { it.close() } }
    }
}

/**
 * The open book's contents: a PDF's own outline, or the folders an archive's pages sit in. A
 * book with neither returns nothing, and the reader shows no contents button.
 */
private fun contentsOf(source: Closeable): List<TocEntry> = runCatching {
    if (source is PdfDocument) {
        source.outline().map { TocEntry(it.title, it.pageIndex, it.depth) }
    } else {
        Toc.fromEntryNames((source as ComicSource).pages.map { it.entryName })
    }
}.getOrDefault(emptyList())
