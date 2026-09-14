package com.absolutex.core.decode

/** A tile's position in the source image's pixel space, plus the subsampling to decode it at. */
data class Tile(
    val col: Int,
    val row: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sampleSize: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Splits a page into tiles and decides which ones the current viewport needs.
 *
 * The reader keeps one low-res base layer resident for the whole page and streams high-res
 * tiles only for what is on screen (§3). Tiles are addressed in SOURCE pixel space so the
 * grid never has to be rebuilt as the user zooms — only [sampleSizeFor] changes.
 */
object TileGrid {

    /**
     * 512px keeps a 4-byte-per-pixel tile at 1 MB, so a 400%-zoomed 1240x2772 viewport needs
     * roughly a dozen resident tiles. Larger tiles waste decode work at the edges; smaller
     * ones multiply per-tile BitmapRegionDecoder overhead.
     */
    const val TILE_SIZE = 512

    /**
     * Largest power-of-two subsample that still renders at or above display density.
     *
     * Power-of-two only, because BitmapRegionDecoder silently rounds inSampleSize down to one
     * anyway — computing a non-power-of-two here would mean the tile we cache is not the tile
     * we asked for, and the cache key would lie.
     */
    fun sampleSizeFor(scale: Float): Int {
        if (scale <= 0f) return 1
        var sample = 1
        // Halve while the full-res pixels would be more than 2x what the screen can show.
        while (scale * sample * 2 <= 1f) sample *= 2
        return sample.coerceAtLeast(1)
    }

    fun columns(imageWidth: Int): Int = ceilDiv(imageWidth, TILE_SIZE)
    fun rows(imageHeight: Int): Int = ceilDiv(imageHeight, TILE_SIZE)

    /**
     * Tiles intersecting the viewport, in source pixel coordinates.
     *
     * @param viewport visible region in SOURCE pixel space (already un-transformed by pan/zoom)
     * @param overscan extra rings of tiles to fetch beyond the viewport, hiding pop-in when
     *   panning. 1 ring is the right default: 0 pops visibly, 2 doubles decode work.
     */
    fun visibleTiles(
        imageWidth: Int,
        imageHeight: Int,
        viewLeft: Int,
        viewTop: Int,
        viewRight: Int,
        viewBottom: Int,
        scale: Float,
        overscan: Int = 1,
    ): List<Tile> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        // Empty or inverted viewport asks for nothing rather than the whole page.
        if (viewRight <= viewLeft || viewBottom <= viewTop) return emptyList()

        val maxCol = columns(imageWidth) - 1
        val maxRow = rows(imageHeight) - 1

        val c0 = (floorDiv(viewLeft, TILE_SIZE) - overscan).coerceIn(0, maxCol)
        val c1 = (floorDiv(viewRight - 1, TILE_SIZE) + overscan).coerceIn(0, maxCol)
        val r0 = (floorDiv(viewTop, TILE_SIZE) - overscan).coerceIn(0, maxRow)
        val r1 = (floorDiv(viewBottom - 1, TILE_SIZE) + overscan).coerceIn(0, maxRow)

        val sample = sampleSizeFor(scale)
        val out = ArrayList<Tile>((c1 - c0 + 1) * (r1 - r0 + 1))
        for (row in r0..r1) {
            for (col in c0..c1) {
                out += Tile(
                    col = col,
                    row = row,
                    left = col * TILE_SIZE,
                    top = row * TILE_SIZE,
                    // Edge tiles are clipped to the image, never past it.
                    right = ((col + 1) * TILE_SIZE).coerceAtMost(imageWidth),
                    bottom = ((row + 1) * TILE_SIZE).coerceAtMost(imageHeight),
                    sampleSize = sample,
                )
            }
        }
        return out
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
    private fun floorDiv(a: Int, b: Int) = if (a >= 0) a / b else -(((-a) + b - 1) / b)
}
