package com.absolutex.source.pdf

/**
 * One entry of a PDF outline ("bookmarks"), flattened in pre-order.
 *
 * The tree shape is carried by [depth] rather than by nested children: a reader renders the
 * table of contents as an indented list, and a flat list with a depth column is both what
 * that needs and what survives the JNI boundary without building a node graph in C.
 */
data class PdfOutlineEntry(
    val title: String,
    /** 0 for a top-level entry. */
    val depth: Int,
    /** 0-based page, or [UNRESOLVED] when the entry points outside this document. */
    val pageIndex: Int,
) {
    val isResolved: Boolean get() = pageIndex != UNRESOLVED

    companion object {
        const val UNRESOLVED: Int = -1
    }
}
