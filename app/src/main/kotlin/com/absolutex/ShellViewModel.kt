package com.absolutex

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.LastBookStore
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.AppPrefsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val lastBookStore: LastBookStore,
    @ApplicationContext private val context: Context,
    prefs: AppPrefsSource,
) : ViewModel() {

    /**
     * Theme settings, null until the store has been read once. The splash stays up while it is
     * null, so a dark or true-black user never sees a light first frame flash before the stored
     * value lands.
     */
    val appPrefs: StateFlow<AppPrefs?> = prefs.appPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    suspend fun lastBook(): String? = lastBookStore.get()

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
