package com.absolutex

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.util.Log
import com.absolutex.core.data.ContentResolverTree
import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.data.settings.SettingsWriter
import com.absolutex.core.scan.TreeEntry
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.LastBookStore
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.AppPrefsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "Shell"

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val lastBookStore: LastBookStore,
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val tree: ContentResolverTree,
    private val writer: SettingsWriter,
    prefs: AppPrefsSource,
) : ViewModel() {

    /**
     * Adds a folder the user granted and scans it (§5.1).
     *
     * The grant is persisted first: a location the app cannot reopen after a reboot is worse than
     * no location, because the library would keep its books and never be able to open one.
     */
    fun addLocation(treeUri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { Log.w(TAG, "persistable grant refused for a location", it) }
            if (context.contentResolver.persistedUriPermissions.none { it.uri == treeUri }) {
                Log.w(TAG, "grant not persisted; not adding this location")
                return@launch
            }
            writer.updateApp { it.copy(locations = it.locations + treeUri.toString()) }
            scan(treeUri)
        }
    }

    /**
     * Rescans every stored location. Cheap to call: a scan only writes rows whose content changed,
     * and a location whose grant is gone is dropped rather than scanned into nothing.
     */
    fun rescanLocations() {
        viewModelScope.launch {
            val stored = appPrefs.filterNotNull().first().locations
            val held = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
            val (live, dead) = stored.partition { it in held }
            if (dead.isNotEmpty()) writer.updateApp { it.copy(locations = it.locations - dead.toSet()) }
            live.forEach { scan(Uri.parse(it)) }
        }
    }

    private suspend fun scan(treeUri: Uri) {
        val name = DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast('/')
        val root = TreeEntry(uri = treeUri.toString(), name = name, isDirectory = true)
        val result = runCatching { library.scanTree(root, tree) }
            .onFailure { Log.w(TAG, "scan failed", it) }
            .getOrNull() ?: return
        Log.i(TAG, "scanned a location: ${result.found} books, ${result.removed} gone")
    }

    /**
     * Theme settings, null until the store has been read once. The splash stays up while it is
     * null, so a dark or true-black user never sees a light first frame flash before the stored
     * value lands.
     */
    val appPrefs: StateFlow<AppPrefs?> = prefs.appPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The book to reopen on launch (§5.2), or null.
     *
     * Checked, not just remembered: a grant can outlive the file it was granted for, and resuming
     * into "Couldn't open this book" is a worse first screen than the library. A book that no
     * longer opens is forgotten here, so the next launch goes straight home.
     */
    suspend fun resumableBook(): Uri? {
        val saved = lastBookStore.get() ?: return null
        val uri = Uri.parse(saved)
        val held = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == saved }
        if (!held) {
            clearLastBook(saved)
            return null
        }
        val readable = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.close() }.isSuccess
        }
        if (!readable) {
            clearLastBook(saved)
            return null
        }
        return uri
    }

    fun rememberBook(uri: String) {
        viewModelScope.launch { lastBookStore.set(uri) }
    }

    /**
     * Forgets the saved book AND releases its persistable grant, so a stale Uri never
     * accumulates grants the app no longer uses. Releasing a grant we don't hold throws
     * SecurityException — hence runCatching.
     */
    fun clearLastBook(uri: String? = null) {
        viewModelScope.launch {
            if (uri != null) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            lastBookStore.clear()
        }
    }
}
