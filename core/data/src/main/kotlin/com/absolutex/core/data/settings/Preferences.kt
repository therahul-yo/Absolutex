package com.absolutex.core.data.settings

import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.NextBookOrder
import com.absolutex.core.data.NextBookScope
import com.absolutex.model.FitContext
import com.absolutex.model.FitMode
import com.absolutex.model.FitModeMemory
import com.absolutex.model.PageLayout
import com.absolutex.model.PageTransition
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
    /** What the settings screen shows and writes everywhere; the reader reads [fitMemory]. */
    val fitMode: FitMode = FitMode.FIT_SCREEN,
    /**
     * The fit for each screen-and-page shape (§5.2). A context never chosen in falls back to
     * [FitModeMemory]'s own default, which is why a spread on a portrait phone starts at fit-width.
     */
    val fitMemory: FitModeMemory = FitModeMemory(),
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
    /** The chrome carries a strip of page thumbnails (§5.2). */
    val thumbnailStrip: Boolean = true,
    /**
     * Pages draw inverted, dark paper and light ink: a white document page at night is a lamp.
     * Off by default, since a colour comic inverted is a negative.
     */
    val darkPages: Boolean = false,
    /** How a page gives way to the next (§5.2). SLIDE is the pager's own, and the plainest. */
    val transition: PageTransition = PageTransition.SLIDE,
    /** How long a page turn animates, in milliseconds (§5.2 animation tuning). */
    val pageTurnMs: Int = DEFAULT_PAGE_TURN_MS,
    /** How far a key press or edge tap scrolls a continuous strip, as a percentage of the screen. */
    val scrollStepPercent: Int = DEFAULT_SCROLL_STEP_PERCENT,
    /**
     * Turn forward past the last page and the reader offers the next book in the series (§5.2).
     * On by default: doing nothing at the end of a book is the surprise, not this.
     */
    val autoAdvance: Boolean = true,
    /** Where auto-advance looks for that next book: the whole library, or just this folder. */
    val nextBookScope: NextBookScope = NextBookScope.WHOLE_LIBRARY,
    /** How the books in that scope are ordered, to find which one comes next. */
    val nextBookOrder: NextBookOrder = NextBookOrder.PARSED_NUMBER,
)

/**
 * The global settings as this book wants them (§5.2). A field the book has not overridden, or
 * overrode with a value this build no longer knows, keeps the global answer rather than resetting
 * to a default nobody chose.
 */
fun ReaderPrefs.overriddenBy(book: BookPrefs?): ReaderPrefs {
    if (book == null) return this
    val flow = book.readingFlow?.let { name -> ReadingFlow.entries.firstOrNull { it.name == name } }
    val layout = book.pageLayout?.let { name -> PageLayout.entries.firstOrNull { it.name == name } }
    return copy(readingFlow = flow ?: readingFlow, pageLayout = layout ?: pageLayout)
}

/** The fit for this shape of screen and page: what was last chosen here, or the shape's default. */
fun ReaderPrefs.fitFor(context: FitContext): FitMode = fitMemory.modeFor(context)

/** Bounds for the animation settings: fast enough to feel instant, slow enough to see. */
const val MIN_PAGE_TURN_MS = 80
const val MAX_PAGE_TURN_MS = 600
const val DEFAULT_PAGE_TURN_MS = 300

/** Below half a screen a step stops being a page turn; a full screen leaves no line of context. */
const val MIN_SCROLL_STEP_PERCENT = 50
const val MAX_SCROLL_STEP_PERCENT = 100
const val DEFAULT_SCROLL_STEP_PERCENT = 90

/** How the reader holds its orientation. */
enum class RotationLock { SYSTEM, PORTRAIT, LANDSCAPE }

/**
 * App-wide flags: the Locations, General, Thumbnails and Rendering settings that are simple
 * scalars. §6 puts these in Preferences DataStore and reader prefs in Proto.
 */
data class AppPrefs(
    val nightMode: NightMode = NightMode.ON,
    val dynamicColour: Boolean = false,
    /** Pure black background in dark mode; on an OLED panel the pixels are actually off. */
    val trueBlack: Boolean = true,
    val cacheSizeMiB: Int = DEFAULT_CACHE_MIB,
    val showHiddenFolders: Boolean = false,
    /** Offer .zip/.rar/.7z/.tar alongside the comic extensions. */
    val openGenericArchives: Boolean = false,
    /**
     * Treat a folder of loose images as a book. Off by default: on a phone the folders that
     * qualify are overwhelmingly photo and messaging-app folders, which then filled the Comics
     * shelf. Someone who keeps comics as loose images turns it on.
     */
    val openImageFolders: Boolean = false,
    /**
     * Show a document's first page as its cover. Off by default: the documents a phone's
     * Downloads holds are often private — identity cards, certificates — and a library grid of
     * their first pages puts them on show. Off, a document gets a typographic placeholder.
     */
    val documentCovers: Boolean = false,
    /** Library locations (§5.1): SAF tree Uris the user granted, as strings. */
    val locations: Set<String> = emptySet(),
    /**
     * §5.1 escape hatch: display and search the raw filename instead of the parse. Off by
     * default — the parse is the better label for almost every real library — and read by the
     * library at display time, so flipping it re-labels without a rescan.
     */
    val useOriginalFilename: Boolean = false,
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
