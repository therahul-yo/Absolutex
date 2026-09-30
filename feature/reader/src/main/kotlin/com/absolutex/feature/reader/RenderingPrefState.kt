package com.absolutex.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.gpu.Upscaler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    val flow = remember(vm) { vm.renderingPrefs.map { it.upscaler }.distinctUntilChanged() }
    val upscaler by flow.collectAsStateWithLifecycle(initialValue = Upscaler.PLATFORM)
    return upscaler
}
