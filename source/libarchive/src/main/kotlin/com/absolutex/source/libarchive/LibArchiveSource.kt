package com.absolutex.source.libarchive

import android.os.ParcelFileDescriptor
import com.absolutex.model.Page
import com.absolutex.source.ComicSource
import com.absolutex.source.EntryFilter
import com.absolutex.source.NaturalOrder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Covers .cbz/.cbr/.cb7/.cbt through one libarchive reader.
 *
 * Takes a [ParcelFileDescriptor] rather than a path because SAF is the default access path
 * (§5.1) and only ever hands out an fd. That is also why java.util.zip.ZipFile is not used
 * for .cbz — it requires a File.
 */
class LibArchiveSource private constructor(
    private val pfd: ParcelFileDescriptor,
    override val pages: List<Page>,
) : ComicSource {

    override fun openPage(index: Int): InputStream {
        val page = pages.getOrNull(index)
            ?: throw IndexOutOfBoundsException("page $index of ${pages.size}")
        val bytes = LibArchive.nativeExtract(pfd.fd, page.entryName)
            ?: throw IOException("unreadable entry: ${page.entryName}")
        return ByteArrayInputStream(bytes)
    }

    override fun close() = pfd.close()

    companion object {
        /**
         * @throws IOException if the container cannot be read at all. A container that reads
         * partially yields the pages that are readable — §2 requires degrading, never crashing.
         */
        fun open(pfd: ParcelFileDescriptor): LibArchiveSource {
            val names = LibArchive.nativeList(pfd.fd)
                ?: throw IOException("not a readable archive")
            val pages = names
                .filter { EntryFilter.isPage(it) }
                .sortedWith(NaturalOrder)
                .mapIndexed { i, name -> Page(index = i, entryName = name) }
            return LibArchiveSource(pfd, pages)
        }
    }
}
