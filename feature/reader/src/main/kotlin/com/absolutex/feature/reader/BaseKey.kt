package com.absolutex.feature.reader

/**
 * A base layer's cache key: one full decoded bitmap for one page of one book, at one measured
 * size (see `ReaderViewModel.baseLayer`). File-level, not nested in `ReaderViewModel`, so
 * [baseKeysToKeep] below is a plain unit test rather than something only a device resize exercises.
 */
internal data class BaseKey(val bookId: String, val page: Int, val width: Int, val height: Int)

/**
 * Which of [keys] survive a successful decode of [justDecoded]: the settled page's neighbourhood
 * ([window] pages either side), and for the page [justDecoded] belongs to, only that size — never
 * every size the page has ever been measured at.
 *
 * Without the second half of that rule, a page that gets remeasured more than once while it stays
 * within the window — a multi-window drag-resize, a foldable fold/unfold, or simply rotating the
 * device, since [BaseKey] carries width and height and neither eviction path used to compare them
 * — keeps one ~9 MB bitmap per size instead of the one the window's budget assumes.
 */
internal fun baseKeysToKeep(keys: Set<BaseKey>, settledPage: Int, window: Int, justDecoded: BaseKey): Set<BaseKey> =
    keys.filterTo(mutableSetOf()) { key ->
        kotlin.math.abs(key.page - settledPage) <= window && (key.page != justDecoded.page || key == justDecoded)
    }
