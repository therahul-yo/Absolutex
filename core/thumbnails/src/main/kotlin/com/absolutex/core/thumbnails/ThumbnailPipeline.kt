package com.absolutex.core.thumbnails

import android.graphics.Bitmap
import com.absolutex.source.ComicSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * Cancellation is per request, not per caller: every in-flight [ThumbRequest] maps to one shared
 * [Deferred] owned by the pipeline's own [scope], never to the calling coroutine's job. Two callers
 * asking for the same request join the same `Deferred` instead of both decoding, and [cancel] drops
 * only that shared unit of work, so cancelling one request can never take a sibling load down with
 * it just because they happened to run in the same caller coroutine. Batch loads fan out under the
 * same scope with a concurrency limit, so one failure (or one cancellation) reports a per-key
 * [Result] and never fails the batch. After [close] the instance is retired; build a fresh one to
 * load again.
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
    private val inFlight = ConcurrentHashMap<ThumbRequest, Deferred<Bitmap>>()

    /**
     * Loads one thumbnail, consulting memory, then disk, then the source. Corrupt source bytes
     * throw [java.io.IOException] and are never cached, so a retry re-reads the source.
     *
     * The actual load runs in a child job owned by the pipeline's [scope], shared by key: a
     * concurrent caller for the same [request] joins that job instead of starting a second decode,
     * and this suspending call only ever awaits it, so cancelling this caller's own coroutine never
     * reaches back into the shared work (see [cancel]).
     */
    suspend fun load(
        source: ComicSource,
        request: ThumbRequest,
        config: Bitmap.Config = Bitmap.Config.HARDWARE,
    ): Bitmap {
        memory.get(request)?.let { return it }
        val deferred = inFlight.computeIfAbsent(request) { key ->
            val job = scope.async {
                ensureActive()
                loadFromDisk(request, config) ?: loadFromSource(source, request, config)
            }
            // Removed on completion (success, failure or cancellation) rather than in the caller's
            // finally: several callers can be awaiting the same key, and the entry must go away
            // exactly once, whether or not any of them is still around to run cleanup.
            job.invokeOnCompletion { inFlight.remove(key, job) }
            job
        }
        return deferred.await()
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
        disk.close()
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
        disk.put(ThumbKeys.keyHex(request), encodeWebp(stored))
        // The resident copy matches the caller's config; a failed upload keeps the decodable original.
        val resident = if (stored.config == config) stored else stored.copy(config, false) ?: stored
        memory.put(request, resident)
        return resident
    }

    // Lossy WEBP at this quality is visually indistinguishable for a grid thumbnail but a fraction
    // of PNG's size and encode time: a 512px cover runs hundreds of KB and tens of ms as PNG, versus
    // tens of KB and single-digit ms here, so the disk cap holds far more covers and cold grid fill
    // spends less time compressing.
    private fun encodeWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
        return out.toByteArray()
    }

    companion object {
        // Batch fan-out stays small so sweeping many pages cannot spike native memory with in-flight decodes.
        const val MAX_CONCURRENT_LOADS = 4
        // Resident thumbs cover the visible grid plus overscroll; anything colder re-derives from disk.
        const val DEFAULT_MEMORY_BYTES = 64L * 1024 * 1024
        private const val WEBP_QUALITY = 80
    }
}
