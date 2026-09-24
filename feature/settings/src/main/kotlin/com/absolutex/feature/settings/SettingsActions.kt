package com.absolutex.feature.settings

import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow

/** Every edit the content can make, so SettingsContent stays a pure function of state + actions. */
data class SettingsActions(
    val onNightMode: (NightMode) -> Unit,
    val onDynamicColour: (Boolean) -> Unit,
    val onTrueBlack: (Boolean) -> Unit,
    val onShowHiddenFolders: (Boolean) -> Unit,
    val onOpenGenericArchives: (Boolean) -> Unit,
    val onOpenImageFolders: (Boolean) -> Unit,
    val onReadingFlow: (ReadingFlow) -> Unit,
    val onFitMode: (FitMode) -> Unit,
    /** Every other reader setting: a plain field edit needs no reducer and no action of its own. */
    val onReader: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    val onCacheSize: (Int) -> Unit,
    /** Rendering edits, clamped in SettingsReductions before they reach the store. */
    val onRendering: ((RenderingPrefs) -> RenderingPrefs) -> Unit,
    /** Removes a storage location, its grant and its books. */
    val onRemoveLocation: (String) -> Unit = {},
    val onDocumentCovers: (Boolean) -> Unit = {},
)
