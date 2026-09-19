package com.absolutex.feature.remote

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.remote.core.TransportCoverFetcher
import com.absolutex.remote.core.encodeRemoteUri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** Folder browser states: loading, content, or a listing failure with its path for retry. */
sealed interface BrowseState {
    data object Loading : BrowseState
    data class Content(
        val path: String,
        val rootPath: String,
        val entries: List<RemoteEntry>,
    ) : BrowseState
    data class Failed(val path: String) : BrowseState
}

/** Cover per book path: absent means still loading, so the grid never waits for covers. */
sealed interface CoverState {
    data class Ready(val bytes: ByteArray) : CoverState
    data object Failed : CoverState
}

/**
 * Folder browser for one file server (SMB/FTP). The path lives in the [SavedStateHandle]
 * under the nav-arg key, always in [Uri.encode]d form so a restore decodes exactly once —
 * the same value the browse route carries, so process death and fresh navigation agree.
 * The last opened folder is also remembered per server in [BrowseHistoryStore] for the
 * next launch; the handle wins when both exist.
 *
 * Listing runs on IO and is cancellable: a new navigation cancels the in-flight list and
 * its cover pass. A vanishing server is the normal case (NAS asleep, Wi-Fi roam) and
 * reads as [BrowseState.Failed] with a retry, never a crash.
 */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val browser: RemoteBrowser,
    private val covers: TransportCoverFetcher,
    private val history: BrowseHistoryStore,
) : ViewModel() {

    val serverId: String = requireNotNull(savedStateHandle[KEY_SERVER_ID]) {
        "browse needs a server id"
    }

    private val _state = MutableStateFlow<BrowseState>(BrowseState.Loading)
    val state: StateFlow<BrowseState> = _state

    private val _covers = MutableStateFlow<Map<String, CoverState>>(emptyMap())
    val coversByPath: StateFlow<Map<String, CoverState>> = _covers

    private var listJob: Job? = null
    private var coverJob: Job? = null
    private var rootCache: String? = null

    init {
        val restored = savedStateHandle.get<String>(KEY_PATH)?.let(Uri::decode)
        if (restored != null) {
            load(restored)
        } else {
            viewModelScope.launch {
                load(history.lastDir(serverId) ?: rootOrFallback())
            }
        }
    }

    /** Opens a subfolder: remembers it for restore and history, then lists it. */
    fun openDir(path: String) {
        savedStateHandle[KEY_PATH] = Uri.encode(path)
        load(path, persist = true)
    }

    /** Row tap: folders descend, books open in the reader through [readerUriFor]. */
    fun onEntryTap(entry: RemoteEntry, onOpenBook: (String) -> Unit) {
        if (entry.isDirectory) {
            openDir(entry.path)
        } else {
            onOpenBook(readerUriFor(entry))
        }
    }

    /**
     * Steps one folder up. False at the record root (or while loading), where the host
     * pops the screen instead — the browser never climbs above the server's root.
     */
    fun goUp(): Boolean {
        val current = currentPath() ?: return false
        val parent = parentOf(current) ?: return false
        if (current == rootCache) return false
        openDir(parent)
        return true
    }

    /** Re-lists the current folder after a failure. */
    fun retry() {
        currentPath()?.let(::load)
    }

    /**
     * Reader Uri for a book row: the only minting point, so hostile names (`+`, `%2F`,
     * non-ASCII) round-trip through [com.absolutex.remote.core.parseRemoteUri] exactly.
     */
    fun readerUriFor(entry: RemoteEntry): String =
        encodeRemoteUri(serverId, entry.path)

    private fun load(path: String, persist: Boolean = false) {
        listJob?.cancel()
        coverJob?.cancel()
        _covers.value = emptyMap()
        listJob = viewModelScope.launch {
            _state.value = BrowseState.Loading
            if (persist) {
                // Awaited, not fire-and-forget: the visible folder and the remembered folder
                // stay identical, so process death between navigation and persist cannot lose
                // it. Local milliseconds against a network listing; a corrupt store fails
                // open into the listing instead of blocking it.
                try {
                    history.saveDir(serverId, path)
                } catch (e: IOException) {
                    Log.w(TAG, "history save failed for $serverId", e)
                }
            }
            try {
                val root = rootCache ?: withContext(Dispatchers.IO) {
                    browser.rootPath(serverId)
                }.also { rootCache = it }
                val entries = withContext(Dispatchers.IO) {
                    browser.listDir(serverId, path)
                }
                _state.value = BrowseState.Content(path, root, entries)
                startCovers(entries)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // Deliberately uninspected: every transport failure reads as the same
                // generic message plus retry — the screen owns the wording, not the wire.
                _state.value = BrowseState.Failed(path)
            }
        }
    }

    /**
     * Cover pass over the listed books: bounded concurrency, one transport per book that
     * [TransportCoverFetcher] owns and closes, failures recorded per path so the tile
     * falls back without ever blocking the list. Cancelled by the next navigation.
     */
    private fun startCovers(entries: List<RemoteEntry>) {
        coverJob?.cancel()
        val books = entries.filter { !it.isDirectory }.take(MAX_COVERS_PER_FOLDER)
        if (books.isEmpty()) return
        coverJob = viewModelScope.launch {
            val semaphore = Semaphore(COVER_CONCURRENCY)
            books.map { entry ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val fetched = runCatching {
                            covers.coverBytes(serverId, entry.path)
                        }.getOrNull()
                        ensureActive()
                        val cover = if (fetched != null) {
                            CoverState.Ready(fetched)
                        } else {
                            CoverState.Failed
                        }
                        _covers.update { current -> current + (entry.path to cover) }
                    }
                }
            }.joinAll()
        }
    }

    private suspend fun rootOrFallback(): String =
        runCatching {
            withContext(Dispatchers.IO) { browser.rootPath(serverId) }
        }.getOrNull()?.also { rootCache = it } ?: ROOT_PATH

    private fun currentPath(): String? = when (val current = _state.value) {
        is BrowseState.Content -> current.path
        is BrowseState.Failed -> current.path
        BrowseState.Loading -> savedStateHandle.get<String>(KEY_PATH)?.let(Uri::decode)
    }

    private companion object {
        const val KEY_SERVER_ID = "serverId"
        const val KEY_PATH = "path"
        const val TAG = "Browse"

        /** Simultaneous cover fetches: one transport each, and servers cap connections. */
        const val COVER_CONCURRENCY = 3

        /** Cover pass ceiling per folder: a huge drop lists first, depicts later. */
        const val MAX_COVERS_PER_FOLDER = 200

        fun parentOf(path: String): String? {
            if (path == ROOT_PATH) return null
            val parent = path.trimEnd('/').substringBeforeLast('/', ROOT_PATH)
            if (parent == path || parent.isEmpty()) return ROOT_PATH
            return parent
        }
    }
}
