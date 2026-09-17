package com.absolutex.feature.reader

import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.core.thumbnails.ThumbnailPipeline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds a [ThumbnailPipeline] on first use rather than eagerly, in its own file so `ReaderViewModel`
 * (already at detekt's function-per-class limit) does not have to grow to hold it.
 *
 * Eagerly building the pipeline on every [ReaderViewModel.open] put its disk-journal
 * reconciliation (`ThumbDiskCache.loadOrRebuild`'s directory listing) ahead of the state that
 * gates a book's first page — a real cost a book's first page never needs, since only the chrome's
 * thumbnail strip, opened well after, does.
 */
internal class ThumbPipelineHolder(private val cacheDir: File) {
    private val init = Mutex()
    @Volatile private var pipeline: ThumbnailPipeline? = null

    /** Closes and forgets the current pipeline: for a new book, or ViewModel teardown. */
    fun reset() {
        pipeline?.close()
        pipeline = null
    }

    /**
     * The pipeline, built the first time anything asks and off Main. [init] serialises that first
     * build: [get] is called once per visible thumbnail-strip entry, several near-simultaneously
     * on Main, and an unguarded check-then-set would let two callers each build and hand off a
     * pipeline onto the very same on-disk cache directory.
     */
    suspend fun get(): ThumbnailPipeline = pipeline ?: init.withLock {
        pipeline ?: withContext(DecodeDispatchers.extract) {
            ThumbnailPipeline(File(cacheDir, "thumbs"))
        }.also { pipeline = it }
    }
}
