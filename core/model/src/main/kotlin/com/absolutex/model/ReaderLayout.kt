package com.absolutex.model

/** How the device is held. */
enum class ScreenOrientation { PORTRAIT, LANDSCAPE }

/** The shape of the page itself — a spread is landscape even on a portrait screen. */
enum class PageOrientation {
    PORTRAIT,
    LANDSCAPE,
    ;

    companion object {
        /**
         * A page wider than it is tall is a spread. Square pages count as portrait: they fit a
         * portrait screen without the split a spread needs.
         */
        fun of(width: Int, height: Int): PageOrientation =
            if (width > height) LANDSCAPE else PORTRAIT
    }
}

/**
 * The context a fit mode is remembered against (§5.2).
 *
 * Four combinations, not one global setting, because they genuinely want different answers: a
 * spread on a portrait phone wants fit-width and a scroll, while the same spread held landscape
 * wants fit-screen. Storing one mode means every rotation fights the user.
 */
data class FitContext(val screen: ScreenOrientation, val page: PageOrientation) {
    companion object {
        fun of(screenWidth: Int, screenHeight: Int, pageWidth: Int, pageHeight: Int) = FitContext(
            screen = if (screenWidth > screenHeight) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
            page = PageOrientation.of(pageWidth, pageHeight),
        )
    }
}

/**
 * Remembers a fit mode per context, with sensible defaults for contexts never chosen (§5.2).
 *
 * Immutable: [with] returns a new memory rather than mutating, so this can live in UI state and
 * be persisted as a plain map without anyone worrying about who else holds a reference.
 */
data class FitModeMemory(private val chosen: Map<FitContext, FitMode> = emptyMap()) {

    fun modeFor(context: FitContext): FitMode = chosen[context] ?: defaultFor(context)

    fun with(context: FitContext, mode: FitMode): FitModeMemory =
        FitModeMemory(chosen + (context to mode))

    /** For persistence; the key is stable and readable so a stored map survives a refactor. */
    fun asPairs(): Map<String, String> =
        chosen.entries.associate { (k, v) -> "${k.screen}:${k.page}" to v.name }

    companion object {
        /**
         * Defaults chosen so the first open of each shape is already right:
         * a spread on a portrait screen fits width (readable, scrolls down); everything else
         * fits the screen.
         */
        fun defaultFor(context: FitContext): FitMode = when {
            context.screen == ScreenOrientation.PORTRAIT &&
                context.page == PageOrientation.LANDSCAPE -> FitMode.FIT_WIDTH
            else -> FitMode.FIT_SCREEN
        }

        /** One mode for every context: what "use this fit from now on" means in settings. */
        fun everywhere(mode: FitMode): FitModeMemory = FitModeMemory(
            ScreenOrientation.entries
                .flatMap { screen -> PageOrientation.entries.map { page -> FitContext(screen, page) } }
                .associateWith { mode },
        )

        fun fromPairs(pairs: Map<String, String>): FitModeMemory = FitModeMemory(
            pairs.mapNotNull { (key, value) ->
                val (screen, page) = key.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val context = FitContext(
                    screen = runCatching { ScreenOrientation.valueOf(screen) }.getOrNull() ?: return@mapNotNull null,
                    page = runCatching { PageOrientation.valueOf(page) }.getOrNull() ?: return@mapNotNull null,
                )
                val mode = runCatching { FitMode.valueOf(value) }.getOrNull() ?: return@mapNotNull null
                context to mode
            }.toMap(),
        )
    }
}

/**
 * Maps between a page's number in the book and its position in the pager (§5.2 reading flows).
 *
 * RTL is the whole reason this exists: a right-to-left book is the same pages in the same order,
 * displayed so that swiping left goes *back*. Reversing at the pager boundary keeps every other
 * part of the reader — prefetch, progress, bookmarks — working in book order, with no RTL
 * special cases scattered through them.
 */
object PageOrder {

    fun pagerIndexOf(page: Int, pageCount: Int, flow: ReadingFlow): Int =
        if (flow == ReadingFlow.RTL) lastIndex(pageCount) - page else page

    fun pageAt(pagerIndex: Int, pageCount: Int, flow: ReadingFlow): Int =
        if (flow == ReadingFlow.RTL) lastIndex(pageCount) - pagerIndex else pagerIndex

    private fun lastIndex(pageCount: Int) = (pageCount - 1).coerceAtLeast(0)
}

/** The nine tap targets of §5.2's tap grid, named by position on screen. */
enum class TapZone {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    MIDDLE_LEFT, CENTER, MIDDLE_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
}

/**
 * Which column of the grid a zone sits in: 0 left, 1 centre, 2 right. The enum is row-major, so the
 * column is the entry's position within its row. Page turns care about the column alone.
 */
val TapZone.column: Int get() = ordinal % TapGrid.CELLS

/**
 * Splits the reader surface into the 9-zone tap grid (§5.2).
 *
 * [mirrored] swaps the left and right columns, which is what an RTL book needs: the zone that
 * means "next page" should stay under the same thumb, not jump across the screen.
 */
object TapGrid {

    /** Rows and columns: §5.2's grid is 3 x 3. */
    const val CELLS = 3
    private const val LAST_CELL = CELLS - 1

    fun zoneAt(x: Float, y: Float, width: Int, height: Int, mirrored: Boolean = false): TapZone {
        if (width <= 0 || height <= 0) return TapZone.CENTER
        val column = cellOf(x, width).let { if (mirrored) LAST_CELL - it else it }
        val row = cellOf(y, height)
        return TapZone.entries[row * CELLS + column]
    }

    /**
     * Which third a coordinate falls in, clamped.
     *
     * Clamping matters: a gesture can report a coordinate a pixel or two outside the view during
     * a fling or on a device with a cutout, and an unclamped index walks off the enum.
     */
    private fun cellOf(value: Float, extent: Int): Int =
        ((value / extent) * CELLS).toInt().coerceIn(0, LAST_CELL)
}
