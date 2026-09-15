package com.absolutex.core.media

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.absolutex.core.scan.LibraryChange
import com.absolutex.core.scan.LibraryScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SAF-side complement to the filesystem watcher (§5.1; the watcher lane covers filesystem
 * roots with WatchService and names a MediaStore observer as the follow-up for SAF locations).
 * Emits the shared [LibraryChange] by resolving changed MediaStore row IDs to paths.
 *
 * Coverage, stated honestly: MediaStore only indexes media volumes, so this sees shared
 * storage it indexes — Downloads, Pictures and sibling shared dirs on VOLUME_EXTERNAL — and
 * stays silent elsewhere by platform design, not by bug. Invisible: SAF tree locations
 * outside indexed volumes (OTG drives, unindexed SD cards, app-private dirs), which never
 * produce MediaStore rows for any observer to see.
 *
 * No permissions are requested here: READ_MEDIA_* is lead/Play-policy territory, so without
 * grants every row resolves to null and each flush degrades to RescanRequested (TODO).
 * TODO(repo-wiring): LibraryRepository owns consuming this (Added/Modified → upsert scan,
 * Removed → delete, RescanRequested → re-walk); the repository lane owns that wiring.
 *
 * @param scope hosts the debounce flush; pass an IO-backed scope in production, because the
 *   row/dir reads block. Tests drive [handleChange] with synthetic change URIs on virtual time.
 */
class MediaStoreObserver(
    private val contentResolver: ContentResolver,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val includeHidden: Boolean = false,
    private val rowSource: MediaRowSource = PlatformMediaRowSource(contentResolver),
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val pendingIds = LinkedHashSet<Long>()
    private val pendingSelf = HashSet<Long>()
    private var pendingUnknown = 0
    private var flushJob: Job? = null
    private val selfExportPaths = HashSet<String>()
    private val knownFolderBooks = HashSet<String>()
    private var activeObserver: ContentObserver? = null
    private var channelSend: ((LibraryChange) -> Unit)? = null

    /** Cold: nothing is registered until collected; collection in a scope the caller owns. */
    fun changes(): Flow<LibraryChange> = callbackFlow {
        if (closed.get()) {
            close()
        } else {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
                    handleChange(selfChange, uri)
                }
            }
            synchronized(this@MediaStoreObserver) { activeObserver = observer }
            contentResolver.registerContentObserver(FILES_EXTERNAL, true, observer)
            channelSend = { change -> trySend(change) }
            try {
                awaitClose {
                    unregister(observer)
                    channelSend = null
                }
            } finally {
                // Leaked observers drain binder: always unregister, even if collection throws.
                unregister(observer)
                channelSend = null
            }
        }
    }.buffer(LibraryScanner.CHANNEL_CAPACITY)

    /**
     * Records one platform notification. Row URIs carry their ID in the last segment; a
     * dir-level URI names no row, so it can only ever become a RescanRequested.
     */
    internal fun handleChange(selfChange: Boolean, uri: Uri?) {
        if (closed.get()) return
        synchronized(this) {
            val id = uri?.lastPathSegment?.toLongOrNull()
            if (id == null) {
                pendingUnknown++
            } else {
                pendingIds.add(id)
                if (selfChange) pendingSelf.add(id)
            }
            flushJob?.cancel()
            // Debounce: MediaStore fires per-row + per-thumbnail-row; batch the storm.
            flushJob = scope.launch {
                delay(debounceMs)
                flush()
            }
        }
    }

    /**
     * Call after exporting a file so its arrival never reads back as a library change;
     * without this every export triggers a rescan loop.
     */
    fun notifySelfExport(path: String) {
        synchronized(this) { selfExportPaths.add(path) }
    }

    private data class PendingFlush(val ids: List<Long>, val self: Set<Long>, val unknown: Int)

    private class FlushBatch {
        val events = ArrayList<LibraryChange>()
        val folderDirs = LinkedHashSet<String>()
        var unmapped = 0
    }

    private fun drainPending(): PendingFlush {
        synchronized(this) {
            // Overflowed IDs can no longer be named, so they degrade like unknowns.
            val unknown = pendingUnknown +
                if (MediaChangeClassifier.overflowed(pendingIds)) 1 else 0
            val drained = PendingFlush(
                MediaChangeClassifier.coalesce(pendingIds),
                pendingSelf.toSet(),
                unknown,
            )
            pendingIds.clear()
            pendingSelf.clear()
            pendingUnknown = 0
            return drained
        }
    }

    private fun flush() {
        val pending = drainPending()
        if (closed.get()) return
        val batch = FlushBatch().also { it.unmapped = pending.unknown }
        for (id in pending.ids) resolveRow(id, pending.self, batch)
        evaluateFolders(batch)
        if (batch.unmapped > 0) batch.events.add(LibraryChange.RescanRequested)
        val send = synchronized(this) { channelSend }
        for (event in batch.events) send?.invoke(event)
    }

    private fun resolveRow(id: Long, self: Set<Long>, batch: FlushBatch) {
        // Our own write: skip before any query, so exports never loop back into rescans.
        if (id in self) return
        when (val row = runCatching { rowSource.rowFor(id) }.getOrNull()) {
            // A deleted row leaves no path to name, so count it toward an honest rescan.
            null -> batch.unmapped++
            else -> stageRow(row, batch)
        }
    }

    private fun stageRow(row: MediaRow, batch: FlushBatch) {
        if (row.path in selfExportPaths || MediaChangeClassifier.isSelfExport(row.path)) return
        val mapped = MediaChangeClassifier.classifyRow(row.path, includeHidden)
        if (mapped != null) {
            batch.events.add(mapped)
            return
        }
        if (MediaChangeClassifier.needsFolderEvaluation(row.path, includeHidden)) {
            File(row.path).parent?.let { batch.folderDirs.add(it) }
        }
    }

    private fun evaluateFolders(batch: FlushBatch) {
        val dirs = batch.folderDirs.toList()
        // One census per parent dir, bounded: extra dirs degrade rather than wedge the flush.
        for (dir in dirs.take(MediaChangeClassifier.MAX_DIR_QUERIES_PER_FLUSH)) {
            val stats = runCatching { rowSource.statsFor(File(dir)) }.getOrNull() ?: continue
            val wasBook = dir in knownFolderBooks
            val event = MediaChangeClassifier.classifyFolder(dir, stats, wasBook, includeHidden)
            if (event is LibraryChange.FolderPromoted || event is LibraryChange.Modified) {
                knownFolderBooks.add(dir)
            } else {
                knownFolderBooks.remove(dir)
            }
            event?.let { batch.events.add(it) }
        }
        if (dirs.size > MediaChangeClassifier.MAX_DIR_QUERIES_PER_FLUSH) batch.unmapped++
    }

    private fun unregister(observer: ContentObserver) {
        synchronized(this) {
            if (activeObserver === observer) activeObserver = null
        }
        runCatching { contentResolver.unregisterContentObserver(observer) }
    }

    /** Idempotent: safe to call twice, and safe after collection already cancelled. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(this) {
                flushJob?.cancel()
                flushJob = null
                channelSend = null
            }
            val observer = synchronized(this) {
                activeObserver.also { activeObserver = null }
            }
            observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        }
    }

    companion object {
        /**
         * Quiet period before a burst of row notifications resolves and emits. Bulk imports
         * and thumbnail rows arrive as storms; sub-second absorbs them without making single
         * adds feel laggy. TODO(device): tune the delay against real MediaStore burst shapes.
         */
        const val DEBOUNCE_MS = 750L

        /** The indexed shared-storage volume; SAF trees outside it never appear here. */
        val FILES_EXTERNAL: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }
}
