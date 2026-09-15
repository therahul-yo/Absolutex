package com.absolutex.feature.reader

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Trace
import android.os.ParcelFileDescriptor
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
import com.absolutex.core.decode.TileCache
import com.absolutex.model.BookIdentity
import com.absolutex.source.ComicSource
import com.absolutex.source.libarchive.LibArchiveSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val progressDao: ProgressDao,
    @TotalRamBytes private val totalRamBytes: Long,
    prefs: ReaderPrefsSource,
) : ViewModel() {

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
    private val _failedPages = MutableStateFlow<Set<Int>>(emptySet())
    val failedPages: StateFlow<Set<Int>> = _failedPages.asStateFlow()

    val tileCache = TileCache(MemoryBudget.defaultCacheBytes(totalRamBytes))

    private var source: ComicSource? = null
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

    private data class BaseKey(val bookId: String, val page: Int, val width: Int, val height: Int)
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
        if (openedUri == uri.toString() && source != null) return
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
                withContext(DecodeDispatchers.extract) {
                    // A fresh descriptor per read — a shared SAF fd corrupts parallel reads.
                    LibArchiveSource.open { openDescriptor(uri) } to identityOf(uri)
                }
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
            if (generation != openGeneration.get()) {
                runCatching { source0.close() }
                return@launch
            }
            // Close the old source only now, immediately before replacing, so in-flight
            // page decodes against it fail cleanly instead of racing a premature close.
            val old = source
            source = source0
            openedUri = uri.toString()
            bookId = identity
            runCatching { old?.close() }
            val resume = progressDao.get(bookId)?.pageIndex ?: 0
            _ui.value = ReaderUiState(
                loading = false,
                title = uri.lastPathSegment?.substringAfterLast('/').orEmpty(),
                pageCount = source0.pages.size,
                bookId = bookId,
                currentPage = resume.coerceIn(0, (source0.pages.size - 1).coerceAtLeast(0)),
            )
        }
    }

    /**
     * The book's identity, however it was reached — see BookIdentity. Keying progress by the Uri
     * string gave one comic a different identity per route, so the library could never match a
     * shelf entry to its reading position.
     */
    private fun identityOf(uri: Uri): String = when (uri.scheme) {
        "file", null -> uri.path?.let { java.io.File(it) }
            ?.let { BookIdentity.ofOrFallback(it.name, it.length(), uri.toString()) }
            ?: uri.toString()
        else -> context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
            val size = c.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            BookIdentity.ofOrFallback(name, size, uri.toString())
        } ?: uri.toString()
    }

    /**
     * Opens a descriptor for either a SAF document or a plain file path.
     *
     * §5.1 needs device-storage locations, which are real paths, not content Uris — and a
     * file path also avoids SAF entirely where the app already has access, which is both
     * faster and what makes the reader drivable from an instrumented benchmark.
     */
    private fun openDescriptor(uri: Uri): ParcelFileDescriptor = when (uri.scheme) {
        // Messages below stay generic: the raw Uri must not reach the UI (nor be
        // formatted into exceptions that the UI renders) — logcat gets the detail.
        "file", null -> ParcelFileDescriptor.open(
            java.io.File(requireNotNull(uri.path) { "file uri has no path" }),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        else -> context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("could not open document")
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

        val bytes = try {
            withContext(DecodeDispatchers.extract) { src.openPage(index).readBytes() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return failPage(index, e)
        }

        val decoded = try {
            withContext(DecodeDispatchers.decode) { PageImage.from(bytes) }
        } catch (e: CancellationException) {
            throw e
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
            // Keep only the settled page's neighbourhood; a far base is cheap to decode again.
            bases.keys.removeIf { kotlin.math.abs(it.page - settledPage) > BASE_WINDOW }
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
        pageImages.values.forEach { runCatching { it.close() } }
        pageImages.clear()
        bases.clear()
        tileCache.clear()
        runCatching { source?.close() }
        source = null
        super.onCleared()
    }

    /** Halves the tile budget on memory pressure; called from MainActivity's callbacks. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            tileCache.trimToSize(tileCache.maxBytes() / 2)
            bases.keys.removeIf { it.page != settledPage }
        }
    }
}
