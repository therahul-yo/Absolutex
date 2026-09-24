package com.absolutex.feature.settings

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.data.NextBookOrder
import com.absolutex.core.data.NextBookScope
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.MAX_PAGE_TURN_MS
import com.absolutex.core.data.settings.MAX_SCROLL_STEP_PERCENT
import com.absolutex.core.data.settings.MIN_PAGE_TURN_MS
import com.absolutex.core.data.settings.MIN_SCROLL_STEP_PERCENT
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.data.settings.RotationLock
import com.absolutex.core.gpu.ColourPanel
import com.absolutex.core.gpu.Upscaler
import com.absolutex.model.PageLayout
import com.absolutex.model.PageTransition
import com.absolutex.core.ui.AbsolutexTheme
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow

@Composable
fun SettingsScreen(
    onOpenRemote: () -> Unit = {},
    onAddLocation: () -> Unit = {},
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = hiltViewModel(),
    locationsVm: LocationsViewModel = hiltViewModel(),
) {
    val app by vm.appPrefs.collectAsStateWithLifecycle()
    val reader by vm.readerPrefs.collectAsStateWithLifecycle()
    val rendering by vm.renderingPrefs.collectAsStateWithLifecycle()
    val locations by locationsVm.locations.collectAsStateWithLifecycle()
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
            rendering = rendering,
            actions = SettingsActions(
                onNightMode = vm::setNightMode,
                onDynamicColour = vm::setDynamicColour,
                onTrueBlack = vm::setTrueBlack,
                onShowHiddenFolders = vm::setShowHiddenFolders,
                onOpenGenericArchives = vm::setOpenGenericArchives,
                onOpenImageFolders = vm::setOpenImageFolders,
                onReadingFlow = vm::setReadingFlow,
                onFitMode = vm::setFitMode,
                onReader = vm::updateReader,
                onCacheSize = vm::setCacheSize,
                onRendering = vm::updateRendering,
                onRemoveLocation = locationsVm::remove,
                onDocumentCovers = locationsVm::setDocumentCovers,
            ),
            locations = locations,
            onOpenRemote = onOpenRemote,
            onAddLocation = onAddLocation,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    app: AppPrefs,
    reader: ReaderPrefs,
    rendering: RenderingPrefs,
    actions: SettingsActions,
    onOpenRemote: () -> Unit = {},
    onAddLocation: () -> Unit = {},
    modifier: Modifier = Modifier,
    locations: List<StorageLocation> = emptyList(),
) {
    // The large title collapses into the bar as the page scrolls, as Material 3 settings do.
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(title = { Text(stringResource(R.string.settings_title)) }, scrollBehavior = scroll)
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            GroupHeader(R.string.settings_group_general)
            SettingsCard { GeneralGroup(app, actions) }

            GroupHeader(R.string.settings_group_library)
            SettingsCard {
                locations.forEach { LocationRow(it, actions.onRemoveLocation) }
                AddStorageLocationRow(onAdd = onAddLocation)
                LibraryGroup(app, actions)
            }

            GroupHeader(R.string.settings_group_reader)
            SettingsCard { ReaderGroup(reader, actions) }

            GroupHeader(R.string.settings_group_rendering)
            SettingsCard { RenderingGroup(app, rendering, actions) }

            GroupHeader(R.string.settings_group_remote)
            SettingsCard { RemoteServersRow(onOpen = onOpenRemote) }

            GroupHeader(R.string.settings_group_about)
            SettingsCard {
                AboutRow()
                OpenSourceLicencesRow()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RenderingGroup(app: AppPrefs, rendering: RenderingPrefs, actions: SettingsActions) {
    CacheSizeRow(valueMiB = app.cacheSizeMiB, onChange = actions.onCacheSize)
    // The same panel the reader chrome hosts: one composable, one state, no copies.
    ColourPanel(
        state = rendering.colour,
        onChange = { actions.onRendering { current -> current.withColour(it) } },
    )
    SegmentedSettingRow(
        options = Upscaler.entries,
        selected = rendering.upscaler,
        onSelect = { actions.onRendering { current -> current.copy(upscaler = it) } },
        labelRes = ::upscalerLabelRes,
        descriptionRes = R.string.settings_upscaler_desc,
    )
    SwitchSettingRow(
        titleRes = R.string.settings_auto_background,
        checked = rendering.autoBackground,
        onChange = { on -> actions.onRendering { it.copy(autoBackground = on) } },
        descriptionRes = R.string.settings_auto_background_desc,
    )
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
        SwitchSettingRow(
            titleRes = R.string.settings_document_covers,
            checked = app.documentCovers,
            onChange = actions.onDocumentCovers,
            descriptionRes = R.string.settings_document_covers_desc,
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
        LayoutRows(reader, actions)
        AnimationRows(reader, actions)
        SegmentedSettingRow(
            options = RotationLock.entries,
            selected = reader.rotationLock,
            onSelect = { lock -> actions.onReader { it.copy(rotationLock = lock) } },
            labelRes = ::rotationLockLabelRes,
            descriptionRes = R.string.settings_rotation_desc,
        )
        SwitchSettingRow(
            titleRes = R.string.settings_thumbnail_strip,
            checked = reader.thumbnailStrip,
            onChange = { on -> actions.onReader { it.copy(thumbnailStrip = on) } },
            descriptionRes = R.string.settings_thumbnail_strip_desc,
        )
        SwitchSettingRow(
            titleRes = R.string.settings_keep_screen_on,
            checked = reader.keepScreenOn,
            onChange = { on -> actions.onReader { it.copy(keepScreenOn = on) } },
            descriptionRes = R.string.settings_keep_screen_on_desc,
        )
        SwitchSettingRow(
            titleRes = R.string.settings_use_cutout,
            checked = reader.useCutout,
            onChange = { on -> actions.onReader { it.copy(useCutout = on) } },
            descriptionRes = R.string.settings_use_cutout_desc,
        )
        SwitchSettingRow(
            titleRes = R.string.settings_volume_keys,
            checked = reader.volumeKeysTurnPages,
            onChange = { on -> actions.onReader { it.copy(volumeKeysTurnPages = on) } },
            descriptionRes = R.string.settings_volume_keys_desc,
        )
        AutoAdvanceRows(reader, actions)
    }
}

/** §5.2 auto-advance: on/off, plus where and how it looks for the next book. */
@Composable
private fun AutoAdvanceRows(reader: ReaderPrefs, actions: SettingsActions) {
    SwitchSettingRow(
        titleRes = R.string.settings_auto_advance,
        checked = reader.autoAdvance,
        onChange = { on -> actions.onReader { it.copy(autoAdvance = on) } },
        descriptionRes = R.string.settings_auto_advance_desc,
    )
    SegmentedSettingRow(
        options = NextBookScope.entries,
        selected = reader.nextBookScope,
        onSelect = { scope -> actions.onReader { it.copy(nextBookScope = scope) } },
        labelRes = ::nextBookScopeLabelRes,
        descriptionRes = R.string.settings_next_book_scope_desc,
    )
    SegmentedSettingRow(
        options = NextBookOrder.entries,
        selected = reader.nextBookOrder,
        onSelect = { order -> actions.onReader { it.copy(nextBookOrder = order) } },
        labelRes = ::nextBookOrderLabelRes,
        descriptionRes = R.string.settings_next_book_order_desc,
    )
}

/** §5.2's animation tuning: how long a page turn takes, and how far a strip scrolls per step. */
@Composable
private fun AnimationRows(reader: ReaderPrefs, actions: SettingsActions) {
    NumberSliderRow(
        valueLabelRes = R.string.settings_page_turn_value,
        value = reader.pageTurnMs,
        range = MIN_PAGE_TURN_MS..MAX_PAGE_TURN_MS,
        onChange = { ms -> actions.onReader { it.copy(pageTurnMs = ms) } },
        descriptionRes = R.string.settings_page_turn_desc,
    )
    NumberSliderRow(
        valueLabelRes = R.string.settings_scroll_step_value,
        value = reader.scrollStepPercent,
        range = MIN_SCROLL_STEP_PERCENT..MAX_SCROLL_STEP_PERCENT,
        onChange = { percent -> actions.onReader { it.copy(scrollStepPercent = percent) } },
        descriptionRes = R.string.settings_scroll_step_desc,
    )
}

/**
 * Page layout and transition. Radio rows, like the fit modes: four of each will not fit a
 * segmented row on a narrow phone.
 */
@Composable
private fun LayoutRows(reader: ReaderPrefs, actions: SettingsActions) {
    PageLayout.entries.forEach { layout ->
        RadioSettingRow(
            titleRes = pageLayoutLabelRes(layout),
            selected = reader.pageLayout == layout,
            onClick = { actions.onReader { it.copy(pageLayout = layout) } },
        )
    }
    PageTransition.entries.forEach { transition ->
        RadioSettingRow(
            titleRes = transitionLabelRes(transition),
            selected = reader.transition == transition,
            onClick = { actions.onReader { it.copy(transition = transition) } },
        )
    }
}

@Composable
private fun GroupHeader(titleRes: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A group's rows on one rounded tonal card, so sections read as units without dividers. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), content = content)
    }
}
