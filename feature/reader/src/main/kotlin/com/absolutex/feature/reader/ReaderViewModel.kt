package com.absolutex.feature.reader

import android.content.ComponentCallbacks2
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val pageImages = HashMap<Int, PageImage>()

    fun open(uri: Uri) {
        if (bookId == uri.toString() && source != null) return
        _ui.value = ReaderUiState(loading = true)
        viewModelScope.launch {
            runCatching {
                withContext(DecodeDispatchers.extract) {
                    // A fresh descriptor per read — a shared SAF fd corrupts parallel reads.
                    LibArchiveSource.open { openDescriptor(uri) }
                }
            }.onSuccess { opened ->
                source = opened
                bookId = uri.toString()
                val resume = progressDao.get(bookId)?.pageIndex ?: 0
                _ui.value = ReaderUiState(
                    loading = false,
                    title = uri.lastPathSegment?.substringAfterLast('/').orEmpty(),
                    pageCount = opened.pages.size,
                    currentPage = resume.coerceIn(0, (opened.pages.size - 1).coerceAtLeast(0)),
                )
            }.onFailure { t ->
                _ui.value = ReaderUiState(loading = false, error = t.message ?: "failed to open")
            }
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
        // Staged: archive I/O on extract, pixel decode on decode. The book stays open on
        // extract (open() above); only the byte[] crosses to the decode pool.
        val bytes = try {
            withContext(DecodeDispatchers.extract) {
                src.openPage(index).readBytes()
            }
        } catch (_: Exception) {
            return null
        }
        return try {
            withContext(DecodeDispatchers.decode) {
                PageImage.from(bytes)
            }.also { pageImages[index] = it }
        } catch (_: Exception) {
            null
        }
    }

    fun onPageChanged(index: Int) {
        if (index == _ui.value.currentPage) return
        _ui.value = _ui.value.copy(currentPage = index)
        val id = bookId
        if (id.isEmpty()) return
        viewModelScope.launch {
            progressDao.upsert(
                ReadingProgress(
                    bookId = id,
                    pageIndex = index,
                    pageCount = _ui.value.pageCount,
                    // Every row carries its own timestamp: §5.5 sync is last-write-wins.
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onCleared() {
        pageImages.values.forEach { it.close() }
        pageImages.clear()
        tileCache.clear()
        runCatching { source?.close() }
        super.onCleared()
    }

    /** Halves the tile budget on memory pressure; called from MainActivity's callbacks. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            tileCache.trimToSize(tileCache.maxBytes() / 2)
        }
    }
}
