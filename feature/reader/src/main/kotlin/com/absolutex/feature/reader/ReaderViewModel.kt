package com.absolutex.feature.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.TotalRamBytes
import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.core.decode.MemoryBudget
import com.absolutex.core.decode.PageImage
import com.absolutex.core.decode.TileCache
import com.absolutex.source.ComicSource
import com.absolutex.source.libarchive.LibArchiveSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class ReaderUiState(
    val loading: Boolean = false,
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val progressDao: ProgressDao,
    @TotalRamBytes private val totalRamBytes: Long,
) : ViewModel() {

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    val tileCache = TileCache(MemoryBudget.defaultCacheBytes(totalRamBytes))

    private var source: ComicSource? = null
    private var bookId: String = ""
    // Thread-safe for get-on-Main + put-on-decode-pool; bounded to a sliding window
    // around the current page (see evictFarPages). ConcurrentHashMap needs the manual
    // bound because it has no access-order eviction of its own.
    private val pageImages = ConcurrentHashMap<Int, PageImage>()
    private val openGeneration = AtomicInteger(0)
    private var openJob: Job? = null

    companion object {
        /** Resident decoded pages. ~12 covers viewport + prefetch without ballooning native heap. */
        const val MAX_RESIDENT_PAGES = 12
    }

    fun open(uri: Uri) {
        if (bookId == uri.toString() && source != null) return
        // Cancel any in-flight open so a rapid book switch cannot land stale state.
        openJob?.cancel()
        val generation = openGeneration.incrementAndGet()
        // Drop the previous book's decoded state now so tiles/pages cannot alias
        // across books while the new archive extracts.
        tileCache.clear()
        pageImages.values.forEach { runCatching { it.close() } }
        pageImages.clear()
        _ui.value = ReaderUiState(loading = true)
        openJob = viewModelScope.launch {
            val opened = try {
                withContext(DecodeDispatchers.extract) {
                    // A fresh descriptor per read — a shared SAF fd corrupts parallel reads.
                    LibArchiveSource.open { openDescriptor(uri) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (generation == openGeneration.get()) {
                    _ui.value = ReaderUiState(loading = false, error = t.message ?: "failed to open")
                }
                return@launch
            }
            if (generation != openGeneration.get()) {
                runCatching { opened.close() }
                return@launch
            }
            // Close the old source only now, immediately before replacing, so in-flight
            // page decodes against it fail cleanly instead of racing a premature close.
            val old = source
            source = opened
            bookId = uri.toString()
            runCatching { old?.close() }
            val resume = progressDao.get(bookId)?.pageIndex ?: 0
            _ui.value = ReaderUiState(
                loading = false,
                title = uri.lastPathSegment?.substringAfterLast('/').orEmpty(),
                pageCount = opened.pages.size,
                currentPage = resume.coerceIn(0, (opened.pages.size - 1).coerceAtLeast(0)),
            )
        }
    }

    /**
     * Opens a descriptor for either a SAF document or a plain file path.
     *
     * §5.1 needs device-storage locations, which are real paths, not content Uris — and a
     * file path also avoids SAF entirely where the app already has access, which is both
     * faster and what makes the reader drivable from an instrumented benchmark.
     */
    private fun openDescriptor(uri: Uri): ParcelFileDescriptor = when (uri.scheme) {
        "file", null -> ParcelFileDescriptor.open(
            java.io.File(requireNotNull(uri.path) { "file uri has no path: $uri" }),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        else -> context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("could not open document: $uri")
    }

    /** Decoded page, cached. Called off the main thread by the reader. */
    suspend fun pageImage(index: Int): PageImage? {
        val src = source ?: return null
        pageImages[index]?.let { return it }
        return withContext(DecodeDispatchers.decode) {
            // Fast re-check inside the pool: another decode may have won the race.
            pageImages[index]?.let { return@withContext it }
            val decoded = runCatching {
                val bytes = src.openPage(index).readBytes()
                PageImage.from(bytes)
            }.getOrNull() ?: return@withContext null
            // Never cache poison: corrupt decodes (width<=0) are closed and dropped.
            if (decoded.width <= 0 || decoded.height <= 0) {
                runCatching { decoded.close() }
                return@withContext null
            }
            val prev = pageImages.putIfAbsent(index, decoded)
            if (prev != null) {
                runCatching { decoded.close() }
                prev
            } else {
                evictFarPages()
                decoded
            }
        }
    }

    /** Clears one cached page so the UI retry path forces a fresh decode. */
    fun invalidatePage(index: Int) {
        pageImages.remove(index)?.let { runCatching { it.close() } }
    }

    /** Keeps only a sliding window around the current page; closes evicted pages. */
    private fun evictFarPages() {
        val center = _ui.value.currentPage
        while (pageImages.size > MAX_RESIDENT_PAGES) {
            val farthest = pageImages.keys.maxByOrNull { kotlin.math.abs(it - center) } ?: break
            val removed = pageImages.remove(farthest) ?: break
            runCatching { removed.close() }
        }
    }

    fun onPageChanged(index: Int) {
        if (index == _ui.value.currentPage) return
        _ui.value = _ui.value.copy(currentPage = index)
        val id = bookId
        // Capture before launching: a book switch mid-write must not persist the new
        // book's index against the old count (or vice versa).
        val count = _ui.value.pageCount
        if (id.isEmpty()) return
        viewModelScope.launch {
            progressDao.upsert(
                ReadingProgress(
                    bookId = id,
                    pageIndex = index,
                    pageCount = count,
                    // Every row carries its own timestamp: §5.5 sync is last-write-wins.
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onCleared() {
        openJob?.cancel()
        pageImages.values.forEach { runCatching { it.close() } }
        pageImages.clear()
        tileCache.clear()
        runCatching { source?.close() }
        source = null
        super.onCleared()
    }
}
