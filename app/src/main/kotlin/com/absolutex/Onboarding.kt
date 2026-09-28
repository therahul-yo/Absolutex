package com.absolutex

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Gap))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Edge))
        content()
    }
}

@Composable
private fun WelcomePage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        AsciiTitle(stringResource(R.string.app_name), fallback = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(Edge))
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
        ReadingFlow.LTR to R.string.onboarding_flow_ltr,
        ReadingFlow.RTL to R.string.onboarding_flow_rtl,
        ReadingFlow.VERTICAL to R.string.onboarding_flow_vertical,
    )
    OnboardingPage(
        stringResource(R.string.onboarding_reading_title),
        stringResource(R.string.onboarding_reading_body),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            flows.forEachIndexed { i, (flow, label) ->
                SegmentedButton(
                    selected = reader.readingFlow == flow,
                    onClick = { vm.setReadingFlow(flow) },
                    shape = SegmentedButtonDefaults.itemShape(i, flows.size),
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.height(Edge))
        ChoiceRow(
            R.string.onboarding_true_black, R.string.onboarding_true_black_body, app.trueBlack, vm::setTrueBlack,
        )
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
        Spacer(Modifier.height(Gap))
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Gap)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
private val DotSize = 8.dp
private val DotWide = 24.dp
private val DotGap = 8.dp
private val ScrimStops = arrayOf(
    0f to Color.Black.copy(alpha = 0.2f),
    0.3f to Color.Black.copy(alpha = 0.75f),
    0.7f to Color.Black.copy(alpha = 0.75f),
    1f to Color.Black.copy(alpha = 0.2f),
)
