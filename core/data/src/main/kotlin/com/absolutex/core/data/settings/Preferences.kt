package com.absolutex.core.data.settings

import com.absolutex.model.FitMode
import com.absolutex.model.PageLayout
import com.absolutex.model.ReadingFlow

/** Night mode, as §5.4 groups it under General. */
enum class NightMode { OFF, ON, SYSTEM }

/**
 * Reader behaviour the reader itself reads (§5.4, Reader group).
 *
 * Deliberately only the two settings that have behaviour behind them today. §5.4 asks for a
 * larger Reader group, but the brief also forbids shipping a control that silently does nothing,
 * so the rest stays out of the model until the behaviour exists rather than sitting here as a
 * field nobody honours.
 */
data class ReaderPrefs(
    val readingFlow: ReadingFlow = ReadingFlow.LTR,
    val fitMode: FitMode = FitMode.FIT_SCREEN,
    /**
     * Volume keys turn pages (§5.3). Off by default: taking over the volume keys silently is the
     * kind of surprise a reader should ask for, not impose.
     */
    val volumeKeysTurnPages: Boolean = false,
    /** The screen stays on while a page is open (§5.2). Reading is not an idle phone. */
    val keepScreenOn: Boolean = true,
    /** Rotation lock while reading (§5.2). SYSTEM follows the device's own rotation setting. */
    val rotationLock: RotationLock = RotationLock.SYSTEM,
    /**
     * Pages draw under the display cutout (§5.2). On by default: a camera hole-punch covers a few
     * pixels of margin, and letterboxing the whole page away from it wastes a strip of screen.
     */
    val useCutout: Boolean = true,
    /** One page per screen, or facing pages side by side (§5.2 page layouts). */
    val pageLayout: PageLayout = PageLayout.SINGLE,
)

/** How the reader holds its orientation. */
enum class RotationLock { SYSTEM, PORTRAIT, LANDSCAPE }

/**
 * App-wide flags: the Locations, General, Thumbnails and Rendering settings that are simple
 * scalars. §6 puts these in Preferences DataStore and reader prefs in Proto.
 */
data class AppPrefs(
    val nightMode: NightMode = NightMode.SYSTEM,
    val dynamicColour: Boolean = true,
    /** Pure black background in dark mode; on an OLED panel the pixels are actually off. */
    val trueBlack: Boolean = false,
    val cacheSizeMiB: Int = DEFAULT_CACHE_MIB,
    val showHiddenFolders: Boolean = false,
    /** Offer .zip/.rar/.7z/.tar alongside the comic extensions. */
    val openGenericArchives: Boolean = false,
    /** Treat a folder of loose images as a book. */
    val openImageFolders: Boolean = true,
) {
    companion object {
        const val DEFAULT_CACHE_MIB: Int = 512

        /**
         * Bounds for [cacheSizeMiB]. The floor is roughly two decoded flagship-resolution pages,
         * below which the reader would evict a page it is about to need; the ceiling is a guard
         * against a corrupt store, not a hardware limit — the real ceiling is MemoryBudget's
         * share of device RAM, which is applied at decode time and may be lower than this.
         */
        const val MIN_CACHE_MIB: Int = 64
        const val MAX_CACHE_MIB: Int = 4096
    }
}
