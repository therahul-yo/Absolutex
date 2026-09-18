package com.absolutex.feature.settings

import com.absolutex.core.data.NextBookOrder
import com.absolutex.core.data.NextBookScope

/**
 * Labels for §5.2 auto-advance's two settings. Its own file: SettingsControls.kt is already at
 * the file's function limit, and these are exactly the kind of one-line enum-to-string map that
 * does not belong bundled with the row composables.
 */
fun nextBookScopeLabelRes(scope: NextBookScope): Int = when (scope) {
    NextBookScope.WHOLE_LIBRARY -> R.string.settings_next_book_scope_library
    NextBookScope.CURRENT_FOLDER -> R.string.settings_next_book_scope_folder
}

fun nextBookOrderLabelRes(order: NextBookOrder): Int = when (order) {
    NextBookOrder.PARSED_NUMBER -> R.string.settings_next_book_order_parsed
    NextBookOrder.RAW_FILENAME -> R.string.settings_next_book_order_filename
}
