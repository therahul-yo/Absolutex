package com.absolutex.source

/**
 * Recovery report, separate from pagination: [readablePageCount] counts complete, nonempty
 * encoded payloads, not decoded images. [totalPageCount] is metadata's declared total, or null
 * if unavailable/inconsistent. Neither count creates, removes or renumbers [ComicSource.pages].
 */
data class PageReadability(val readablePageCount: Int, val totalPageCount: Int?) {
    init {
        require(readablePageCount >= 0)
        require(totalPageCount == null || totalPageCount >= readablePageCount)
    }

    companion object {
        /** Only used on recovery, so ordinary opens need not decompress every page. */
        fun inspect(
            ordinals: List<Int>,
            declaredTotal: Int?,
            isReadable: (Int) -> Boolean,
        ): PageReadability = PageReadability(
            readablePageCount = ordinals.count(isReadable),
            // A partial directory is NOT a total. Reject metadata contradicted by that directory.
            totalPageCount = declaredTotal?.takeIf { it > 0 && it >= ordinals.size },
        )
    }
}
