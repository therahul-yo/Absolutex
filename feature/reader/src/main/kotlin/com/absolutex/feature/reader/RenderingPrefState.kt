package com.absolutex.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class RenderingPrefState(
    val colour: ColourParams,
    val upscaler: Upscaler,
    val enhanceEnabled: Boolean,
)

internal fun readerRenderingState(prefs: RenderingPrefs): RenderingPrefState = RenderingPrefState(
    colour = prefs.effectiveColour,
    upscaler = prefs.effectiveUpscaler,
    enhanceEnabled = prefs.enhanceEnabled,
)

internal fun readerShowsEnhance(isTextEpub: Boolean): Boolean = !isTextEpub

/**
 * The live automatic-background switch, seeded from the preference's current value like
 * [rememberCropEnabled]. It decides whether a page with crop off still samples its edge colour;
 * it never touches the crop itself.
 */
@Composable
internal fun rememberAutoBackground(vm: ReaderViewModel): Boolean {
    val flow = remember(vm) { vm.renderingPrefs.map { it.autoBackground }.distinctUntilChanged() }
    val seed = remember(vm) { vm.renderingPrefs.value.autoBackground }
    val on by flow.collectAsStateWithLifecycle(initialValue = seed)
    return on
}

/**
 * The live upscaler. It recomposes only on a real switch: distinctUntilChanged keeps a colour
 * change (a new RenderingPrefs instance) from recomposing the slot through this read. The
 * operators run inside remember, not composition (FlowOperatorInvokedInComposition).
 */
@Composable
internal fun rememberUpscaler(vm: ReaderViewModel): Upscaler {
    val flow = remember(vm) {
        vm.renderingPrefs.map { readerRenderingState(it).upscaler }.distinctUntilChanged()
    }
    val seed = remember(vm) { readerRenderingState(vm.renderingPrefs.value).upscaler }
    val upscaler by flow.collectAsStateWithLifecycle(initialValue = seed)
    return upscaler
}
