package com.absolutex.core.thumbnails

import android.graphics.Bitmap
import com.absolutex.source.ComicSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Separate thumbnail pipeline: memory LRU, then disk LRU, then source decode, on its own bounded
 * dispatcher ([ThumbnailDispatchers]) so thumbnail work can never starve the reader pool.
 *
 * Takes [ComicSource] as input so the pipeline is usable the day the reader wires it; the source
 * stays open for the pipeline's lifetime and is never closed here.
 *
 * Cancellation is per request: every in-flight [ThumbRequest] maps to its coroutine job, and
 * [cancel] drops exactly that job. Batch loads fan out under the instance [SupervisorJob] scope
 * with a concurrency limit, so one failure (or one cancellation) reports a per-key [Result] and
 * never fails the batch. After [close] the instance is retired; build a fresh one to load again.
 *
 * TODO(thumbs): wire into the reader/library screens once the lead-owned files expose a hook.
 * TODO(thumbs): reuse bitmaps via inBitmap once pool sizing is measured on device, not guessed.
 */
class ThumbnailPipeline(
    cacheDir: File,
    memoryBytes: Long = DEFAULT_MEMORY_BYTES,
    diskBytes: Long = ThumbDiskCache.DISK_CAP_BYTES,
) {
    private val memory = ThumbMemoryCache(memoryBytes)
    private val disk = ThumbDiskCache(cacheDir, diskBytes)
    private val scope = CoroutineScope(SupervisorJob() + ThumbnailDispatchers.thumbnails)
    private val inFlight = ConcurrentHashMap<ThumbRequest, Job>()

    /**
     * Loads one thumbnail, consulting memory, then disk, then the source. Corrupt source bytes
     * throw [java.io.IOException] and are never cached, so a retry re-reads the source.
     */
    suspend fun load(
        source: ComicSource,
        request: ThumbRequest,
        config: Bitmap.Config = Bitmap.Config.HARDWARE,
    ): Bitmap {
        val owner = currentCoroutineContext()[Job]
        if (owner != null) inFlight[request] = owner
        try {
            memory.get(request)?.let { return it }
            return withContext(ThumbnailDispatchers.thumbnails) {
                ensureActive()
                loadFromDisk(request, config) ?: loadFromSource(source, request, config)
            }
        } finally {
            if (owner != null) inFlight.remove(request, owner)
        }
    }

    /**
     * Loads many thumbnails with bounded concurrency. Each key maps to its own [Result]: one
     * failure never fails the batch.
     */
    suspend fun load(
        source: ComicSource,
        requests: List<ThumbRequest>,
        config: Bitmap.Config = Bitmap.Config.HARDWARE,
    ): Map<ThumbRequest, Result<Bitmap>> {
        val semaphore = Semaphore(MAX_CONCURRENT_LOADS)
        val deferreds = requests.map { request ->
            scope.async {
                ensureActive()
                semaphore.withPermit { runCatching { load(source, request, config) } }
            }
        }
        return awaitBatch(requests, deferreds)
    }

    /** Cancels the in-flight load for [request], if any; unknown keys are a no-op. */
    fun cancel(request: ThumbRequest) {
        inFlight[request]?.cancel()
    }

    fun cancelAll() {
        inFlight.values.forEach { it.cancel() }
    }

    fun clearMemory() = memory.clear()

    fun close() {
        cancelAll()
        scope.cancel()
    }

    private suspend fun awaitBatch(
        requests: List<ThumbRequest>,
        deferreds: List<Deferred<Result<Bitmap>>>,
    ): Map<ThumbRequest, Result<Bitmap>> {
        try {
            return requests.zip(deferreds.awaitAll()).toMap()
        } catch (e: CancellationException) {
            deferreds.forEach { it.cancel() }
            throw e
        }
    }

    private suspend fun loadFromDisk(request: ThumbRequest, config: Bitmap.Config): Bitmap? {
        val bytes = disk.get(ThumbKeys.keyHex(request)) ?: return null
        currentCoroutineContext().ensureActive()
        return runCatching {
            val bitmap = ThumbDecoder.decode(bytes, request.widthBucket, config)
            memory.put(request, bitmap)
            bitmap
        }.getOrElse {
            // A corrupt disk entry degrades to a source re-derive: evict it and report a miss.
            disk.remove(ThumbKeys.keyHex(request))
            null
        }
    }

    private suspend fun loadFromSource(
        source: ComicSource,
        request: ThumbRequest,
        config: Bitmap.Config,
    ): Bitmap {
        val bytes = source.openPage(request.pageIndex).use { it.readBytes() }
        // Failed decodes throw before any put, so poison is never cached and a retry re-reads the source.
        currentCoroutineContext().ensureActive()
        // Disk entries decode ARGB_8888: hardware bitmaps cannot persist and cannot be read back.
        val stored = ThumbDecoder.decode(bytes, request.widthBucket, Bitmap.Config.ARGB_8888)
        currentCoroutineContext().ensureActive()
        disk.put(ThumbKeys.keyHex(request), encodePng(stored))
        // The resident copy matches the caller's config; a failed upload keeps the decodable original.
        val resident = if (stored.config == config) stored else stored.copy(config, false) ?: stored
        memory.put(request, resident)
        return resident
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED, out)
        return out.toByteArray()
    }

    companion object {
        // Batch fan-out stays small so sweeping many pages cannot spike native memory with in-flight decodes.
        const val MAX_CONCURRENT_LOADS = 4
        // Resident thumbs cover the visible grid plus overscroll; anything colder re-derives from disk.
        const val DEFAULT_MEMORY_BYTES = 64L * 1024 * 1024
        private const val PNG_QUALITY_IGNORED = 100
    }
}
