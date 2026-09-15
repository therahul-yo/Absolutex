package com.absolutex.feature.settings

import com.absolutex.core.data.settings.NightMode
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
    val onCacheSize: (Int) -> Unit,
)
