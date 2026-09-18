package com.absolutex.feature.widget

/** One row of the Continue Reading widget; bookId is the BookIdentity key shared by progress and library. */
data class WidgetModel(
    val bookId: String,
    val title: String,
    val pageIndex: Int,
    val pageCount: Int,
    val progressFraction: Float,
    val coverKey: String?,
) {
    companion object {
        private const val MIN_FRACTION = 0f
        private const val MAX_FRACTION = 1f

        /** (pageIndex+1)/pageCount clamped to [0,1]; a zero/negative pageCount yields 0, never NaN. */
        fun fractionFor(pageIndex: Int, pageCount: Int): Float {
            if (pageCount <= 0) return MIN_FRACTION
            return ((pageIndex + 1).toFloat() / pageCount).coerceIn(MIN_FRACTION, MAX_FRACTION)
        }

        fun from(
            bookId: String,
            title: String,
            pageIndex: Int,
            pageCount: Int,
            coverKey: String?,
        ): WidgetModel =
            WidgetModel(bookId, title, pageIndex, pageCount, fractionFor(pageIndex, pageCount), coverKey)
    }
}

/** Library title wins; the filename is the last resort so the widget never shows a blank row. */
fun displayTitle(title: String?, series: String?, path: String): String =
    title ?: series ?: path.substringAfterLast('/')
