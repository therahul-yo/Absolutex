package com.absolutex.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.settings.RenderingPrefsSource
import com.absolutex.core.data.settings.SettingsWriter
import com.absolutex.core.data.settings.toggleEnhance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One persisted global flag drives the control and the reader's effective rendering. */
@HiltViewModel
class EnhanceViewModel @Inject constructor(
    rendering: RenderingPrefsSource,
    private val writer: SettingsWriter,
) : ViewModel() {
    val enabled = rendering.renderingPrefs.map { it.enhanceEnabled }.distinctUntilChanged()

    fun toggle() {
        viewModelScope.launch { writer.toggleEnhance() }
    }
}
