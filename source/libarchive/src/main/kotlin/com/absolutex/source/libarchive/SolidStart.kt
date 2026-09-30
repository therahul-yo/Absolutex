package com.absolutex.source.libarchive

import com.absolutex.source.ComicInfoParser
import java.io.File

/** What a probe of an archive found: whether it is solid, and what its entries declare in all. */
internal class SolidProbe(val solid: Boolean, val totalBytes: Long)

/** Everything starting a cache needs from outside: the archive, the disk and a thread. */
internal class SolidLaunch(
    val probe: () -> SolidProbe,
    val streamer: EntryStreamer,
    val freeBytes: () -> Long,
    val spawn: (Runnable) -> Unit,
    val writer: PageWriter = PageWriter.Default,
)

/** The book being opened, as the cache sees it: its ordinals, which of them are pages, and ComicInfo's. */
internal class SolidBook(val entryCount: Int, val pageOrdinals: IntArray, val infoOrdinal: Int)

/**
 * The cache for a book about to be opened, or null when it should be read directly as before.
 *
 * Null for everything that is not a solid archive worth caching (the probe decides, so a non-solid
 * archive never touches the disk), for a book too large for the cap, when eviction cannot make
 * room, and when another open source already holds this archive. A cache that already finished
 * is reused with no decoding at all; otherwise one background pass fills a new one.
 */
internal fun startSolidCache(config: SolidCacheConfig, book: SolidBook, launch: SolidLaunch): SolidSession? {
    val store = SolidCacheStore(config.root)
    if (!store.claim(config.key)) return null
    val session = runCatching { openSession(store, config.key, book, launch) }.getOrNull()
    if (session == null) store.release(config.key)
    return session
}

private fun openSession(store: SolidCacheStore, key: String, book: SolidBook, launch: SolidLaunch): SolidSession? {
    if (store.isComplete(key, book.entryCount)) return reuse(store, key, book)
    val probe = launch.probe()
    if (probe.solid) store.discard(key)   // whatever an earlier, unfinished pass left is never trusted
    val cap = if (probe.solid) SolidCachePolicy.capBytes(launch.freeBytes(), store.totalBytes()) else 0L
    val fits = probe.solid && SolidCachePolicy.worthCaching(probe.totalBytes, cap) &&
        store.makeRoom(probe.totalBytes, cap) && store.dir(key).mkdirs()
    return if (fits) begin(store, key, book, launch, probe) else null
}

/** A new cache, filled by one background pass; null if the pass could not be started. */
private fun begin(
    store: SolidCacheStore,
    key: String,
    book: SolidBook,
    launch: SolidLaunch,
    probe: SolidProbe,
): SolidSession? {
    val pages = SolidPages(store.dir(key))
    val session = SolidSession(store, key, book.entryCount, pages, reused = false)
    val wanted = BooleanArray(book.entryCount)
    book.pageOrdinals.forEach { wanted[it] = true }
    if (book.infoOrdinal >= 0) wanted[book.infoOrdinal] = true
    val plan = PassPlan(wanted, book.infoOrdinal, probe.totalBytes)
    val pass = SolidPass(pages, plan, launch.streamer, launch.writer, session::onPassEnd)
    return if (session.start(pass, launch.spawn)) session else null
}

/** A finished cache: every wanted entry is a file, so nothing is decoded and nothing runs. */
private fun reuse(store: SolidCacheStore, key: String, book: SolidBook): SolidSession {
    store.touch(key)
    val pages = SolidPages(store.dir(key))
    book.pageOrdinals.forEach(pages::markReady)
    if (book.infoOrdinal >= 0) {
        pages.markReady(book.infoOrdinal)
        pages.comicInfo = readInfo(pages.fileFor(book.infoOrdinal))
    }
    pages.finish()
    return SolidSession(store, key, book.entryCount, pages, reused = true)
}

private fun readInfo(file: File) = runCatching {
    file.takeIf { it.length() <= SOLID_INFO_MAX_BYTES }?.inputStream()?.use { ComicInfoParser.parse(it) }
}.getOrNull()
