package com.absolutex.source

import com.absolutex.model.ComicInfo
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

    /**
     * Container metadata (§2), or null when the format carries none or the implementation
     * does not read it yet. Default null keeps every existing implementation — including
     * remote openers — compiling untouched.
     */
    val comicInfo: ComicInfo? get() = null
}
