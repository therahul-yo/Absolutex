package com.absolutex.source.epub

import com.absolutex.model.ComicInfo
import com.absolutex.model.Page
import com.absolutex.source.ComicSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A fixed-layout comic EPUB as a [ComicSource] (§2, §6).
 *
 * No native code and no new dependency: an EPUB is a ZIP, `java.util.zip` is stdlib on Android,
 * and the part that is genuinely EPUB is [EpubPackage].
 *
 * **Reading direction rides [comicInfo].** A package declaring `page-progression-direction="rtl"`
 * surfaces as `ComicInfo(readingFlow = RTL)` rather than as a new `ComicSource` member, because
 * that is the same channel a manga CBZ's `<Manga>YesAndRightToLeft</Manga>` already uses. One
 * consumer serves both. Nothing reads `comicInfo` yet — #38 exposed it and no caller consumes
 * it — so an RTL EPUB is *reported* correctly here and will read RTL once that wiring exists.
 */
class EpubComicSource private constructor(
    override val pages: List<Page>,
    override val comicInfo: ComicInfo?,
    private val coverEntryName: String?,
    /** Reads one entry's bytes by name; null when it cannot be read. */
    private val readEntry: (String) -> ByteArray?,
) : ComicSource {

    override fun openPage(index: Int): InputStream {
        val page = pages.getOrNull(index)
            ?: throw IndexOutOfBoundsException("page $index of ${pages.size}")
        val bytes = readEntry(page.entryName)
            ?: throw IOException("unreadable entry: ${page.entryName}")
        return ByteArrayInputStream(bytes)
    }

    /**
     * The OPF's declared cover when it is not simply page one, otherwise page one.
     *
     * [EpubPackage] already drops a cover that duplicates the first page, so this is only ever a
     * genuinely separate image — the jacket art a library shelf wants and the spine does not.
     */
    override fun openCover(): InputStream {
        val cover = coverEntryName ?: return super.openCover()
        val bytes = readEntry(cover) ?: return super.openCover()
        return ByteArrayInputStream(bytes)
    }

    /** Owns nothing: the caller owns whatever [readEntry] closes over. */
    override fun close() = Unit

    companion object {

        /**
         * Opens [entryNames] as a comic EPUB, or explains why it is not one.
         *
         * @param readEntry one entry's bytes by name. Called once per spine document during the
         *   parse and once per page afterwards, so a caller that can only walk forward should
         *   hand in [packageEntryCache]'s map rather than a per-call walk — see its KDoc for the
         *   measured reason.
         */
        fun open(entryNames: List<String>, readEntry: (String) -> ByteArray?): Result =
            when (val book = EpubPackage.parse(entryNames, readEntry)) {
                is EpubBook.Pages -> Result.Comic(
                    EpubComicSource(
                        pages = book.entryNames.mapIndexed { i, name -> Page(index = i, entryName = name) },
                        comicInfo = book.readingFlow?.let { ComicInfo(readingFlow = it) },
                        coverEntryName = book.coverEntryName,
                        readEntry = readEntry,
                    ),
                )
                EpubBook.Reflowable -> Result.TextEpub
                EpubBook.Malformed -> Result.NotAnEpub
            }

        /**
         * Every entry a package parse can need, read in ONE forward pass. Page images are skipped.
         *
         * This exists because of a measurement, not a preference. Resolving a 200-page spine by
         * reading each document separately, through a reader that walks entry headers from the
         * start on every call, took **2,479 ms and moved 13.6 GiB** for a 136 MiB book — 202
         * reads, each crossing on average half the archive. One pass that skips the images takes
         * **64 ms** and caches **59 KiB**, because a spine document is never decoded, only read
         * to find the image it names. A full entry index would be 37 ms; the remaining 27 ms is
         * not worth an index this does not need.
         *
         * (Measured on a JVM ZIP model of the native reader's forward-only walk, warm cache. The
         * ratio is what transfers; the absolute number is not a device measurement.)
         */
        fun packageEntryCache(openStream: () -> InputStream): Map<String, ByteArray> {
            val cache = HashMap<String, ByteArray>()
            ZipInputStream(openStream().buffered(STREAM_BUFFER_BYTES)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    cacheable(zip, entry.name, entry.isDirectory)?.let { cache[entry.name] = it }
                    // nextEntry skips whatever is left of this one, so an image or an oversized
                    // document costs a skip over its bytes and nothing else.
                }
            }
            return cache
        }

        /**
         * One entry's bytes if they are worth keeping, else null having consumed nothing.
         *
         * The header's declared size is never consulted, because it cannot be trusted twice
         * over: a streamed deflated entry reports -1 (its size lives in a data descriptor this
         * has not reached yet), and a hostile one can simply lie. Reading one byte past the cap
         * and discarding on overflow is bounded whatever the header claims.
         */
        private fun cacheable(zip: ZipInputStream, name: String, isDirectory: Boolean): ByteArray? {
            if (isDirectory || isPageImage(name)) return null
            val bytes = zip.readNBytes(MAX_CACHED_ENTRY_BYTES.toInt() + 1)
            return bytes.takeIf { it.size <= MAX_CACHED_ENTRY_BYTES }
        }

        /**
         * Skipped by the cache: images are referenced by name and never parsed, and they are the
         * whole of an EPUB's bulk. Deliberately broader than [com.absolutex.source.EntryFilter],
         * which answers "is this a page" — here the question is only "is this worth reading".
         */
        private fun isPageImage(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "avif", "heif", "heic", "gif", "bmp", "tif", "tiff",
            "jxl", "jp2", "jpx", "j2k",
        )

        /** Package documents are kilobytes; a megabyte is already far past anything real. */
        private const val MAX_CACHED_ENTRY_BYTES = 1L * 1024 * 1024
        private const val STREAM_BUFFER_BYTES = 64 * 1024
    }

    /** What an EPUB turned out to be, from the reader's point of view. */
    sealed interface Result {
        data class Comic(val source: EpubComicSource) : Result

        /**
         * A reflowable text EPUB. A novel is a different product, not a broken comic, and the
         * reader must say so rather than opening a book with no pages.
         */
        data object TextEpub : Result

        /** No container.xml, no package document, or neither could be read. */
        data object NotAnEpub : Result
    }
}
