package com.absolutex.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.AppPrefsSource
import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.data.settings.ReaderPrefsSource
import com.absolutex.core.data.settings.SettingsWriter
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings screen state. Collects the read-only contract sources and forwards every edit to
 * [SettingsWriter]; the transform itself lives in SettingsReductions so it stays JVM-testable.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    appSource: AppPrefsSource,
    readerSource: ReaderPrefsSource,
    private val writer: SettingsWriter,
) : ViewModel() {

    // Contract sources are plain Flows, so materialise with the contract defaults as seed.
    val appPrefs: StateFlow<AppPrefs> =
        appSource.appPrefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPrefs())

    val readerPrefs: StateFlow<ReaderPrefs> = readerSource.readerPrefs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ReaderPrefs(),
    )

    fun setNightMode(mode: NightMode) {
        viewModelScope.launch { writer.updateApp { it.withNightMode(mode) } }
    }

    fun setDynamicColour(enabled: Boolean) {
        viewModelScope.launch { writer.updateApp { it.withDynamicColour(enabled) } }
    }

    fun setTrueBlack(enabled: Boolean) {
        viewModelScope.launch { writer.updateApp { it.withTrueBlack(enabled) } }
    }

    fun setCacheSize(requestedMiB: Int) {
        // Clamp-then-write (see clampCacheSizeMiB): a corrupt store self-heals on next edit.
        viewModelScope.launch { writer.updateApp { it.withCacheSize(requestedMiB) } }
    }

    fun setShowHiddenFolders(show: Boolean) {
        viewModelScope.launch { writer.updateApp { it.withShowHiddenFolders(show) } }
    }

    fun setOpenGenericArchives(open: Boolean) {
        viewModelScope.launch { writer.updateApp { it.withOpenGenericArchives(open) } }
    }

    fun setOpenImageFolders(open: Boolean) {
        viewModelScope.launch { writer.updateApp { it.withOpenImageFolders(open) } }
    }

    fun setReadingFlow(flow: ReadingFlow) {
        viewModelScope.launch { writer.updateReader { it.withReadingFlow(flow) } }
    }

    fun setFitMode(mode: FitMode) {
        viewModelScope.launch { writer.updateReader { it.withFitMode(mode) } }
    }

    fun updateReader(change: (ReaderPrefs) -> ReaderPrefs) {
        viewModelScope.launch { writer.updateReader(change) }
    }
}
