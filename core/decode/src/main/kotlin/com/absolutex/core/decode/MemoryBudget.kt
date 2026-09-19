package com.absolutex.core.decode

/**
 * Bitmap cache ceiling, derived from device RAM rather than hardcoded.
 *
 * The brief's §3 asks for a 1-2 GB resident working set. The reference device (OnePlus 11R)
 * has 8 GB total, which is the floor of the stated hardware class rather than its 12 GB
 * typical; a 2 GB bitmap working set there invites lmkd kills and evicts every background app.
 *
 * This is not a low-end compatibility branch — it scales *up* on a 16 GB phone, which is
 * exactly what §3 says the user-facing setting is for.
 *
 * Bitmaps live in the native heap (API 26+), so Dalvik heap limits and `largeHeap` are
 * irrelevant here and `largeHeap` is deliberately not declared.
 */
object MemoryBudget {

    private const val FRACTION_OF_TOTAL_RAM = 0.15
    const val FLOOR_BYTES = 256L * 1024 * 1024
    const val CEILING_BYTES = 4L * 1024 * 1024 * 1024

    /** @param totalRamBytes from ActivityManager.MemoryInfo.totalMem */
    fun defaultCacheBytes(totalRamBytes: Long): Long =
        (totalRamBytes * FRACTION_OF_TOTAL_RAM).toLong().coerceIn(FLOOR_BYTES, CEILING_BYTES)

    /** A decoded ARGB_8888 page costs 4 bytes per pixel; hardware bitmaps match it closely enough to budget with. */
    fun bytesForPage(width: Int, height: Int): Long = width.toLong() * height.toLong() * 4L

    fun pagesResident(cacheBytes: Long, width: Int, height: Int): Int =
        (cacheBytes / bytesForPage(width, height)).toInt()
}
