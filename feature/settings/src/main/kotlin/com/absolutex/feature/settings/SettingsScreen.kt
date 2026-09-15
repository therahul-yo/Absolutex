package com.absolutex.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.ui.AbsolutexTheme
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val app by vm.appPrefs.collectAsStateWithLifecycle()
    val reader by vm.readerPrefs.collectAsStateWithLifecycle()
    // The screen previews the theme it configures: what you toggle is what you get.
    val dark = when (app.nightMode) {
        NightMode.ON -> true
        NightMode.OFF -> false
        NightMode.SYSTEM -> isSystemInDarkTheme()
    }
    AbsolutexTheme(darkTheme = dark, dynamicColor = app.dynamicColour, trueBlack = app.trueBlack) {
        SettingsContent(
            app = app,
            reader = reader,
            actions = SettingsActions(
                onNightMode = vm::setNightMode,
                onDynamicColour = vm::setDynamicColour,
                onTrueBlack = vm::setTrueBlack,
                onShowHiddenFolders = vm::setShowHiddenFolders,
                onOpenGenericArchives = vm::setOpenGenericArchives,
                onOpenImageFolders = vm::setOpenImageFolders,
                onReadingFlow = vm::setReadingFlow,
                onFitMode = vm::setFitMode,
                onCacheSize = vm::setCacheSize,
            ),
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    app: AppPrefs,
    reader: ReaderPrefs,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(inner),
        ) {
            GroupHeader(R.string.settings_group_general)
            GeneralGroup(app, actions)
            Spacer(Modifier.height(8.dp))

            GroupHeader(R.string.settings_group_library)
            LibraryGroup(app, actions)
            Spacer(Modifier.height(8.dp))

            GroupHeader(R.string.settings_group_reader)
            ReaderGroup(reader, actions)
            Spacer(Modifier.height(8.dp))

            GroupHeader(R.string.settings_group_rendering)
            CacheSizeRow(valueMiB = app.cacheSizeMiB, onChange = actions.onCacheSize)
            Spacer(Modifier.height(8.dp))

            GroupHeader(R.string.settings_group_about)
            AboutRow()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GeneralGroup(app: AppPrefs, actions: SettingsActions) {
    Column {
        SegmentedSettingRow(
            options = NightMode.entries,
            selected = app.nightMode,
            onSelect = actions.onNightMode,
            labelRes = ::nightModeLabelRes,
            descriptionRes = R.string.settings_night_mode_desc,
        )
        SwitchSettingRow(
            titleRes = R.string.settings_dynamic_colour,
            checked = app.dynamicColour,
            onChange = actions.onDynamicColour,
            descriptionRes = R.string.settings_dynamic_colour_desc,
        )
        SwitchSettingRow(
            titleRes = R.string.settings_true_black,
            checked = app.trueBlack,
            onChange = actions.onTrueBlack,
            descriptionRes = R.string.settings_true_black_desc,
        )
        SwitchSettingRow(
            titleRes = R.string.settings_show_hidden,
            checked = app.showHiddenFolders,
            onChange = actions.onShowHiddenFolders,
            descriptionRes = R.string.settings_show_hidden_desc,
        )
    }
}

@Composable
private fun LibraryGroup(app: AppPrefs, actions: SettingsActions) {
    Column {
        SwitchSettingRow(
            titleRes = R.string.settings_generic_archives,
            checked = app.openGenericArchives,
            onChange = actions.onOpenGenericArchives,
            descriptionRes = R.string.settings_generic_archives_desc,
        )
        SwitchSettingRow(
            titleRes = R.string.settings_image_folders,
            checked = app.openImageFolders,
            onChange = actions.onOpenImageFolders,
            descriptionRes = R.string.settings_image_folders_desc,
        )
    }
}

@Composable
private fun ReaderGroup(reader: ReaderPrefs, actions: SettingsActions) {
    Column {
        SegmentedSettingRow(
            options = ReadingFlow.entries,
            selected = reader.readingFlow,
            onSelect = actions.onReadingFlow,
            labelRes = ::readingFlowLabelRes,
            descriptionRes = R.string.settings_reading_flow_desc,
        )
        // Four fit modes will not fit a segmented row on a narrow phone, so radio rows.
        FitMode.entries.forEach { mode ->
            RadioSettingRow(
                titleRes = fitModeLabelRes(mode),
                selected = reader.fitMode == mode,
                onClick = { actions.onFitMode(mode) },
            )
        }
    }
}

@Composable
private fun GroupHeader(titleRes: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 8.dp),
    )
}
