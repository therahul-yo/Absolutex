package com.absolutex.source

import com.absolutex.model.Page
import java.io.Closeable
import java.io.InputStream

/**
 * One implementation per container format. Every method must be safe to call from several
 * threads at once — the decode pipeline fans pages out across the big cores (§3).
 */
interface ComicSource : Closeable {
    val pages: List<Page>

    /** Random-access read of one page. Callers own the stream. */
    fun openPage(index: Int): InputStream

    /** Cover without decompressing the whole container. */
    fun openCover(): InputStream = openPage(0)
}
