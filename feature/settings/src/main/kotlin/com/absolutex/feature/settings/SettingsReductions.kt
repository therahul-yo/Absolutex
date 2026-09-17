package com.absolutex.feature.settings

import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.gpu.ColourParams
import com.absolutex.model.FitMode
import com.absolutex.model.FitModeMemory
import com.absolutex.model.ReadingFlow

/**
 * Pure state reducers for the settings screen.
 *
 * This file deliberately imports nothing Android: every transform here runs on the JVM under
 * plain JUnit, and the ViewModel delegates to these so its own logic is just collect-and-forward.
 */

// Clamp-then-write: a corrupt store (e.g. a hand-edited proto) self-heals the next time any
// setting is edited, because the VM always funnels cache edits through this.
fun clampCacheSizeMiB(requested: Int): Int =
    requested.coerceIn(AppPrefs.MIN_CACHE_MIB, AppPrefs.MAX_CACHE_MIB)

fun AppPrefs.withNightMode(mode: NightMode): AppPrefs = copy(nightMode = mode)

fun AppPrefs.withDynamicColour(enabled: Boolean): AppPrefs = copy(dynamicColour = enabled)

fun AppPrefs.withTrueBlack(enabled: Boolean): AppPrefs = copy(trueBlack = enabled)

fun AppPrefs.withCacheSize(requestedMiB: Int): AppPrefs =
    copy(cacheSizeMiB = clampCacheSizeMiB(requestedMiB))

fun AppPrefs.withShowHiddenFolders(show: Boolean): AppPrefs = copy(showHiddenFolders = show)

fun AppPrefs.withOpenGenericArchives(open: Boolean): AppPrefs = copy(openGenericArchives = open)

fun AppPrefs.withOpenImageFolders(open: Boolean): AppPrefs = copy(openImageFolders = open)

fun ReaderPrefs.withReadingFlow(flow: ReadingFlow): ReaderPrefs = copy(readingFlow = flow)

/**
 * Settings' fit mode is "use this from now on", so it writes every shape's memory as well as the
 * stored choice. The reader's own fit control writes one shape (§5.2).
 */
fun ReaderPrefs.withFitMode(mode: FitMode): ReaderPrefs =
    copy(fitMode = mode, fitMemory = FitModeMemory.everywhere(mode))

/** Colour edits clamp into the slider ranges on the way in (see ColourParams.clamped). */
fun RenderingPrefs.withColour(colour: ColourParams): RenderingPrefs = copy(colour = colour.clamped())
