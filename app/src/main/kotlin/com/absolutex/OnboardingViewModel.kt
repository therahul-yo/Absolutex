package com.absolutex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.AppPrefsSource
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.data.settings.ReaderPrefsSource
import com.absolutex.core.data.settings.SettingsWriter
import com.absolutex.model.ReadingFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The first-launch setup's choices. Each writes the same setting the Settings screen does, as it
 * is made, so leaving halfway keeps what was chosen; [finish] marks the setup seen for good.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    appSource: AppPrefsSource,
    readerSource: ReaderPrefsSource,
    private val writer: SettingsWriter,
) : ViewModel() {
    val app: StateFlow<AppPrefs> = appSource.appPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs())
    val reader: StateFlow<ReaderPrefs> =
        readerSource.readerPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

    fun setReadingFlow(flow: ReadingFlow) = reader { it.copy(readingFlow = flow) }

    fun setTrueBlack(on: Boolean) = app { it.copy(trueBlack = on) }

    fun setDocumentCovers(on: Boolean) = app { it.copy(documentCovers = on) }

    fun setImageFolders(on: Boolean) = app { it.copy(openImageFolders = on) }

    /** Finished or skipped: either way the setup is not shown again. */
    fun finish() = app { it.copy(onboarded = true) }

    private fun app(change: (AppPrefs) -> AppPrefs) {
        viewModelScope.launch { writer.updateApp(change) }
    }

    private fun reader(change: (ReaderPrefs) -> ReaderPrefs) {
        viewModelScope.launch { writer.updateReader(change) }
    }
}
