package com.absolutex.source

import java.io.InputStream

/**
 * One page to write into a CBZ.
 *
 * [open] is a factory rather than an open stream for the same reason a folder book's entries use one: a
 * book being exported may have hundreds of pages, and holding a stream per page would pin a
 * descriptor for every one of them from the moment the export starts.
 *
 * @param entryName the name the page's *source* knows it by. Only its extension survives into the
 *   archive — see [cbzEntryName] for why the written name is not this one.
 */
data class CbzPage(
    val entryName: String,
    val open: () -> InputStream,
)
