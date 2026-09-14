package com.absolutex.model

enum class ReadingFlow { LTR, RTL, VERTICAL }

enum class FitMode { FIT_SCREEN, FULL_SIZE, FIT_WIDTH, FIT_HEIGHT }

/** One page inside a container, identified by the entry name its source knows it by. */
data class Page(
    val index: Int,
    val entryName: String,
    val sizeBytes: Long = -1L,
)

data class Book(
    val id: String,
    val displayName: String,
    val pageCount: Int,
    /** Pages actually readable. Lower than [pageCount] for truncated archives — §2 says degrade, never crash. */
    val readablePageCount: Int = pageCount,
)
