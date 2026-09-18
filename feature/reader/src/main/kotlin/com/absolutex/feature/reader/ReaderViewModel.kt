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
import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.core.decode.MemoryBudget
import com.absolutex.core.decode.PageImage
import com.absolutex.core.thumbnails.ThumbRequest
import com.absolutex.core.thumbnails.ThumbnailPipeline
import com.absolutex.core.decode.TileCache
import com.absolutex.model.BookIdentity
import com.absolutex.model.Toc
import com.absolutex.model.TocEntry
import com.absolutex.source.ComicSource
import com.absolutex.source.pdf.PdfDocument
import java.io.Closeable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
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
)

@HiltViewModel
class ReaderViewModel internal constructor(
    @ApplicationContext private val context: Context,
    private val progressDao: ProgressDao,
    @TotalRamBytes private val totalRamBytes: Long,
    prefs: ReaderPrefsSource,
    private val bookOpener: BookOpener,
) : ViewModel() {

    /**
     * The constructor Hilt uses; only the four Hilt-known params above are real dependencies.
     * Needs its own internal constructor rather than a Kotlin default argument for [bookOpener]
     * because Dagger cannot see Kotlin defaults on an `@Inject` constructor (see
     * LibraryRepository for the same pattern and the same reason). Tests use the internal
     * constructor to supply a fake [BookOpener].
     */
    @Inject constructor(
        @ApplicationContext context: Context,
        progressDao: ProgressDao,
        @TotalRamBytes totalRamBytes: Long,
        prefs: ReaderPrefsSource,
    ) : this(context, progressDao, totalRamBytes, prefs, ContextBookOpener(context))

    /**
     * Reading flow and fit mode, live. Eager so the value is usually in hand before the first page:
     * the archive open that gates rendering takes longer than the first DataStore read.
     */
    val readerPrefs: StateFlow<ReaderPrefs> =
        prefs.readerPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

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

    /** The open book: a [ComicSource] whose pages are encoded images, or a [PdfDocument]. */
    private var source: Closeable? = null

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
    }

    // Throwable on purpose: any failure to open a book must reach the user as one generic
    // message, and the detail is logged. Narrowing would let an unlisted failure crash instead.
    @Suppress("TooGenericExceptionCaught")
    fun open(uri: Uri) {
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
            val opened = try {
                // NonCancellable: bookOpener.open() (openBook()/identityOf() in production — see
                // ContextBookOpener) is a blocking call with no suspension point of its own, so
                // cancelling this job cannot interrupt it — it always runs to completion once
                // started. Without NonCancellable, a cancel landing while it was already finishing
                // still made this resume with a CancellationException, discarding the
                // successfully-opened handle instead of returning it — leaking its fd (and, for a
                // PDF, its native handle) because it was then never reachable by the generation
                // check below, which is what already closes a result superseded by a newer,
                // non-cancelling call to open().
                // TODO(lead): route absolutex-remote:// Uris to RemoteBookOpener; OpenBook stays local.
                withContext(NonCancellable) { bookOpener.open(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Generic UI string: t.message can embed the raw Uri or an archive entry name.
                // The detail goes to logcat only.
                Log.e(TAG, "open failed", t)
                if (generation == openGeneration.get()) {
                    _ui.value = ReaderUiState(
                        loading = false,
                        error = context.getString(R.string.reader_open_failed),
                    )
                }
                return@launch
            }
            val (source0, identity) = opened
            val count = (source0 as? PdfDocument)?.pageCount ?: (source0 as ComicSource).pages.size
            if (generation != openGeneration.get()) {
                runCatching { source0.close() }
                return@launch
            }
            // Close the old source only now, immediately before replacing, so in-flight
            // page decodes against it fail cleanly instead of racing a premature close.
            val old = source
            source = source0
            // Closed now, but not rebuilt until first use (see ThumbPipelineHolder): its
            // disk-journal reconciliation is a real cost that must not land before the state
            // below, which gates the first page.
            thumbs.reset()
            openedUri = uri.toString()
            bookId = identity
            runCatching { old?.close() }
            val resume = progressDao.get(bookId)?.pageIndex ?: 0
            // The previous book's settled page would otherwise stand in until the pager settles.
            settledPage = resume.coerceIn(0, (count - 1).coerceAtLeast(0))
            _ui.value = ReaderUiState(
                loading = false,
                title = uri.lastPathSegment?.substringAfterLast('/').orEmpty(),
                pageCount = count,
                bookId = bookId,
                currentPage = settledPage,
            )
            // After the state that gates the first page: contents are chrome, and a PDF outline is
            // a JNI call whose cost must not land in the tap-to-first-page budget.
            _toc.value = withContext(DecodeDispatchers.extract) { contentsOf(source0) }
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
            evictFarPages()
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

    /** Keeps only a sliding window around the current page; closes evicted pages. */
    private fun evictFarPages() {
        val center = settledPage
        while (pageImages.size > MAX_RESIDENT_PAGES) {
            val farthest = pageImages.keys.maxByOrNull { kotlin.math.abs(it - center) } ?: break
            pageImages.remove(farthest)?.let { runCatching { it.close() } }
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
        _toc.value = emptyList()
        pageImages.values.forEach { runCatching { it.close() } }
        pageImages.clear()
        bases.clear()
        tileCache.clear()
        thumbs.reset()
        runCatching { source?.close() }
        source = null
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
