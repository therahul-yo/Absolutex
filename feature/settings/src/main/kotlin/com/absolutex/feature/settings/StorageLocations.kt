package com.absolutex.feature.settings

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.InstallIn
import dagger.Module
import dagger.Binds
import com.absolutex.core.data.settings.AppPrefsSource
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.data.settings.SettingsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A folder the library scans, as the settings list shows it. */
data class StorageLocation(val uri: String, val label: String)

/**
 * Reads and removes storage locations for the settings list.
 *
 * The label is the folder's own display name, asked of its provider: the tree's document id is
 * "downloads" or "msd:1000093401", which says nothing to a person. Off the main thread, because it
 * is one provider query per location.
 */
interface LocationStore {
    /** The stored locations, named. */
    val locations: Flow<List<StorageLocation>>

    suspend fun remove(uri: String)

    /** Whether documents show their first page as a cover (see AppPrefs.documentCovers). */
    suspend fun setDocumentCovers(show: Boolean)
}

internal class StorageLocations @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val writer: SettingsWriter,
    prefs: AppPrefsSource,
) : LocationStore {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val locations: Flow<List<StorageLocation>> = prefs.appPrefs
        .map { it.locations }
        .distinctUntilChanged()
        .mapLatest { describe(it) }

    private suspend fun describe(uris: Set<String>): List<StorageLocation> = withContext(Dispatchers.IO) {
        uris.map { StorageLocation(it, labelOf(Uri.parse(it))) }.sortedBy { it.label.lowercase() }
    }

    /**
     * Forgets the location, releases its grant and drops its books. Order matters: the prefs go
     * first, so a rescan that races this cannot re-add books from a location being removed.
     */
    override suspend fun setDocumentCovers(show: Boolean) {
        writer.updateApp { it.copy(documentCovers = show) }
    }

    override suspend fun remove(uri: String) {
        writer.updateApp { it.copy(locations = it.locations - uri) }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        library.forgetLocation(uri)
    }

    private fun labelOf(tree: Uri): String {
        val fallback = runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':') }
            .getOrDefault(tree.toString())
        val root = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return fallback
        val name = runCatching {
            context.contentResolver.query(
                root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: fallback
    }
}

/** The settings list's view of [LocationStore]. Its own ViewModel, so settings state stays small. */
@HiltViewModel
class LocationsViewModel @Inject constructor(private val store: LocationStore) : ViewModel() {
    val locations: StateFlow<List<StorageLocation>> =
        store.locations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(uri: String) {
        viewModelScope.launch { store.remove(uri) }
    }

    fun setDocumentCovers(show: Boolean) {
        viewModelScope.launch { store.setDocumentCovers(show) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocationStoreModule {
    @Binds
    abstract fun bind(store: StorageLocations): LocationStore
}
