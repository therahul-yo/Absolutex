package com.absolutex

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.ui.A11y
import com.absolutex.core.ui.AsciiField
import com.absolutex.core.ui.AsciiTitle
import com.absolutex.core.ui.Motion
import com.absolutex.core.ui.rememberHaptics
import com.absolutex.model.ReadingFlow
import kotlinx.coroutines.launch

/**
 * The first-launch setup: four short pages over the drifting character field — welcome, where
 * the comics are, how you read, and the few choices that are personal — each writing its setting
 * as it is made. Skippable from any page; seen once (see [OnboardingViewModel.finish]).
 */
@Composable
internal fun Onboarding(onAddFolder: (Uri) -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val last = pager.currentPage == PAGES - 1
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Still while a page slides, so the swipe has the frame to itself.
        AsciiField(Modifier.fillMaxSize(), paused = pager.isScrollInProgress)
        // Quiet behind the words, the field left bright at the edges.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*ScrimStops)))
        // No Surface under this screen, so text would default to black on the black field.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = Edge)) {
            Row(Modifier.fillMaxWidth().height(A11y.MinTouchTarget), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = vm::finish) { Text(stringResource(R.string.onboarding_skip)) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> FoldersPage(onAddFolder, vm)
                    2 -> ReadingPage(vm)
                    else -> ChoicesPage(vm)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = Edge),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageDots(pager, Modifier.weight(1f))
                Button(
                    onClick = {
                        haptics.confirm()
                        if (last) vm.finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                    modifier = Modifier.height(A11y.MinTouchTarget),
                ) {
                    Text(stringResource(if (last) R.string.onboarding_start else R.string.onboarding_next))
                }
            }
        }
        }
    }
}

/** A page: a heading, a line or two of why, and whatever the page lets you choose. */
@Composable
private fun OnboardingPage(title: String, body: String, content: @Composable () -> Unit = {}) {
    // Top-aligned at one height on every page, so titles line up as the pages slide. Centred,
    // each page's title sat at a different height and jumped on every swipe.
    Column(Modifier.fillMaxSize().padding(top = PageTop)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Gap))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Section))
        content()
    }
}

@Composable
private fun WelcomePage() {
    Column(Modifier.fillMaxSize().padding(top = PageTop)) {
        AsciiTitle(stringResource(R.string.app_name), fallback = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(Section))
        Text(stringResource(R.string.onboarding_tagline), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(Gap))
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FoldersPage(onAddFolder: (Uri) -> Unit, vm: OnboardingViewModel) {
    val app by vm.app.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        if (folder != null) onAddFolder(folder)
    }
    OnboardingPage(
        stringResource(R.string.onboarding_folders_title),
        stringResource(R.string.onboarding_folders_body),
    ) {
        FilledTonalButton(onClick = { picker.launch(null) }, modifier = Modifier.height(A11y.MinTouchTarget)) {
            Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
            Spacer(Modifier.width(Gap))
            Text(stringResource(R.string.onboarding_add_folder))
        }
        val count = app.locations.size
        if (count > 0) {
            Spacer(Modifier.height(Gap))
            Text(
                pluralStringResource(R.plurals.onboarding_folders_added, count, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadingPage(vm: OnboardingViewModel) {
    val reader by vm.reader.collectAsStateWithLifecycle()
    val app by vm.app.collectAsStateWithLifecycle()
    val flows = listOf(
        Triple(ReadingFlow.LTR, R.string.onboarding_flow_ltr, R.string.onboarding_flow_ltr_hint),
        Triple(ReadingFlow.RTL, R.string.onboarding_flow_rtl, R.string.onboarding_flow_rtl_hint),
        Triple(ReadingFlow.VERTICAL, R.string.onboarding_flow_vertical, R.string.onboarding_flow_vertical_hint),
    )
    OnboardingPage(
        stringResource(R.string.onboarding_reading_title),
        stringResource(R.string.onboarding_reading_body),
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Tight)) {
            flows.forEach { (flow, title, hint) ->
                FlowOption(stringResource(title), stringResource(hint), reader.readingFlow == flow) {
                    vm.setReadingFlow(flow)
                }
            }
        }
        Spacer(Modifier.height(Section))
        ChoiceRow(
            R.string.onboarding_true_black, R.string.onboarding_true_black_body, app.trueBlack, vm::setTrueBlack,
        )
    }
}

/** One reading direction as a full-width card: the chosen one filled light, the others outlined. */
@Composable
private fun FlowOption(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (selected) colours.onSurface else Color.Transparent,
        Motion.enter(),
        label = "fill",
    )
    val ink = if (selected) colours.surface else colours.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(fill)
            .border(1.dp, if (selected) Color.Transparent else colours.outlineVariant, MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Card, vertical = Gap),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ink)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = HINT_ALPHA))
    }
}

@Composable
private fun ChoicesPage(vm: OnboardingViewModel) {
    val app by vm.app.collectAsStateWithLifecycle()
    OnboardingPage(
        stringResource(R.string.onboarding_choices_title),
        stringResource(R.string.onboarding_choices_body),
    ) {
        ChoiceRow(
            R.string.onboarding_covers, R.string.onboarding_covers_body, app.documentCovers, vm::setDocumentCovers,
        )
        Spacer(Modifier.height(Section))
        ChoiceRow(
            R.string.onboarding_image_folders,
            R.string.onboarding_image_folders_body,
            app.openImageFolders,
            vm::setImageFolders,
        )
    }
}

@Composable
private fun ChoiceRow(title: Int, body: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Card)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Hair))
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row toggles; the switch only shows the state, so TalkBack hears one control.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Where you are: the current page a short bar, the rest dots. */
@Composable
private fun PageDots(pager: PagerState, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(DotGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PAGES) { page ->
            val current = page == pager.currentPage
            val width by animateDpAsState(if (current) DotWide else DotSize, Motion.enter(), label = "dot")
            val colour by animateColorAsState(
                if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                Motion.enter(),
                label = "dotColour",
            )
            Box(Modifier.size(width, DotSize).clip(CircleShape).background(colour))
        }
    }
}

private const val PAGES = 4
private val Edge = 24.dp
private val Gap = 12.dp
private val Tight = 10.dp
private val Hair = 2.dp
private val Card = 18.dp
private val Section = 28.dp

/** Where every page's content starts, so titles line up from page to page. */
private val PageTop = 96.dp
private const val HINT_ALPHA = 0.7f
private val DotSize = 8.dp
private val DotWide = 24.dp
private val DotGap = 8.dp
private val ScrimStops = arrayOf(
    0f to Color.Transparent,
    0.3f to Color.Black.copy(alpha = 0.35f),
    0.7f to Color.Black.copy(alpha = 0.35f),
    1f to Color.Transparent,
)
