package com.absolutex.core.thumbnails

/**
 * One thumbnail derivation: page [pageIndex] of the book identified by [sourceId],
 * rendered at [widthBucket] pixels wide.
 *
 * All three fields are required with no defaults: a defaulted book id once let every call site
 * silently share one cache namespace, so a new call site must state which book it means.
 * Raw widths never reach the cache — see [of], which snaps them to a bucket first.
 */
data class ThumbRequest(
    val sourceId: String,
    val pageIndex: Int,
    val widthBucket: Int,
) {
    init {
        require(sourceId.isNotBlank())
        require(pageIndex >= 0)
        require(widthBucket == BUCKET_SMALL || widthBucket == BUCKET_LARGE)
    }

    companion object {
        const val BUCKET_SMALL = 256
        const val BUCKET_LARGE = 512

        // Snapping bounds disk variants per book instead of minting one file per pixel width.
        fun snapWidth(width: Int): Int = if (width <= BUCKET_SMALL) BUCKET_SMALL else BUCKET_LARGE

        // TODO(thumbs): tune the bucket split on device; 256/512 starts as grid vs detail.
        fun of(sourceId: String, pageIndex: Int, width: Int): ThumbRequest =
            ThumbRequest(sourceId, pageIndex, snapWidth(width))
    }
}
