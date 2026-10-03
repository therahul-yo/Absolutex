package com.absolutex.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.data.settings.RenderingPrefsSource
import com.absolutex.core.data.settings.SettingsWriter
import com.absolutex.core.data.settings.toggleEnhance
import com.absolutex.core.data.settings.withColour
import com.absolutex.core.data.settings.withUpscaler
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Uses the Settings source/writer directly; no second rendering preferences record. */
@HiltViewModel
class ReaderDisplayViewModel @Inject constructor(
    source: RenderingPrefsSource,
    private val writer: SettingsWriter,
) : ViewModel() {
    val prefs = source.renderingPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, RenderingPrefs())

    private val colours = Channel<ColourParams>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (colour in colours) writer.updateRendering { it.withColour(colour) }
        }
    }

    // One sequential writer, with only the latest queued sample (including the exact release value).
    fun setColour(colour: ColourParams) {
        colours.trySend(colour)
    }

    fun setUpscaler(upscaler: Upscaler) {
        viewModelScope.launch { writer.updateRendering { it.withUpscaler(upscaler) } }
    }

    fun toggle() {
        viewModelScope.launch { writer.toggleEnhance() }
    }
}
