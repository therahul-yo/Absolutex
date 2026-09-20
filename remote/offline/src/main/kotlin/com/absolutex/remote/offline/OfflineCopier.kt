package com.absolutex.remote.offline

import com.absolutex.remote.core.RangeTransport
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** How a copy ended. Exactly one of these, so no caller can read a size that is not final. */
sealed interface OfflineCopyResult {
    /** The whole file is on disk at [file] and ready to open. */
    data class Complete(val file: File, val sizeBytes: Long) : OfflineCopyResult

    /** The caller cancelled. [copiedBytes] survives on disk and a later copy resumes from it. */
    data class Cancelled(val copiedBytes: Long, val totalBytes: Long) : OfflineCopyResult
}

/**
 * Pulls a whole remote file down to local storage, so a book can be read with no network at all.
 *
 * This is also the only way some books open remotely: CBR, CB7 and CBT need a full-file index
 * to seek, which is why [com.absolutex.remote.core.RemoteOpenResult.DownloadRequired] exists.
 * Streaming answers "open this now"; this answers "make this work at all", and "keep it".
 *
 * **A partial copy is never mistaken for a whole one.** Bytes land in a `.part` file and the
 * finished file appears by an atomic rename, so the destination either does not exist or is
 * complete. A process killed mid-copy — which on a phone is the ordinary case, not the
 * exception — leaves a resumable `.part` and never a plausible-looking truncated archive.
 *
 * **Blocking on purpose, and cancellable by lambda.** No coroutines dependency, matching the
 * rest of this lane: the caller runs it off the main thread and passes [cancelled], so a
 * coroutine supplies `{ !isActive }` and a test supplies a counter. Cancellation is checked
 * between chunks, so it lands within one chunk rather than at the end of the file.
 */
class OfflineCopier(private val chunkBytes: Int = DEFAULT_CHUNK_BYTES) {

    init {
        require(chunkBytes > 0) { "chunk must be positive: $chunkBytes" }
    }

    /**
     * Copies [source] to [destination], resuming any matching `.part` left by an earlier run.
     *
     * Returns [OfflineCopyResult.Complete] without transferring anything when [destination]
     * already holds the whole file, which makes "make this available offline" idempotent and
     * cheap to call again.
     */
    @Throws(IOException::class)
    fun copy(
        source: RangeTransport,
        destination: File,
        cancelled: () -> Boolean = { false },
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): OfflineCopyResult {
        val total = source.sizeBytes()
        if (destination.isFile && destination.length() == total) {
            return OfflineCopyResult.Complete(destination, total)
        }
        val part = partFile(destination, total)
        discardStaleParts(destination, keep = part)
        destination.parentFile?.mkdirs()
        val copied = fill(source, part, total, cancelled, onProgress)
        if (copied < total) return OfflineCopyResult.Cancelled(copied, total)
        return publish(part, destination, total)
    }

    /** Appends to [part] until it holds [total] bytes or the caller cancels. */
    private fun fill(
        source: RangeTransport,
        part: File,
        total: Long,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        // A part longer than the file it is named for cannot be a prefix of that file, so it
        // is corrupt rather than ahead: truncating it back would publish whatever bytes it
        // happened to hold. Discard and start over.
        var copied = part.length()
        if (copied > total) {
            part.delete()
            copied = 0
        }
        RandomAccessFile(part, "rw").use { out ->
            out.setLength(copied)
            out.seek(copied)
            onProgress(copied, total)
            while (copied < total) {
                if (cancelled()) return copied
                val want = minOf(chunkBytes.toLong(), total - copied).toInt()
                val chunk = source.readAt(copied, want)
                // RangeTransport is exact-or-throw, so a short answer is a broken transport,
                // not a slow one. Saying so here beats looping on a source that will never
                // advance, and beats writing a gap into the copy.
                if (chunk.size != want) {
                    throw IOException("transport gave ${chunk.size} of $want bytes at $copied")
                }
                out.write(chunk)
                copied += chunk.size.toLong()
                onProgress(copied, total)
            }
        }
        return copied
    }

    /** Verifies the size, then makes the file appear whole in one step. */
    private fun publish(part: File, destination: File, total: Long): OfflineCopyResult {
        if (part.length() != total) {
            throw IOException("copy finished at ${part.length()} bytes, expected $total")
        }
        Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return OfflineCopyResult.Complete(destination, total)
    }

    private companion object {
        /**
         * 1 MiB. Sixteen of BlockCache's 64 KiB blocks, so a chunk never splits one, and for a
         * 300 MB book it is ~300 ranged requests rather than the ~4800 a block-sized chunk
         * would cost — per-request overhead dominates a bulk copy in a way it does not dominate
         * a page turn. One chunk is live at a time, so this is also the heap cost per copy.
         */
        const val DEFAULT_CHUNK_BYTES = 1024 * 1024

        const val PART_SUFFIX = ".part"

        /**
         * `<name>.<total>.part`, so the expected size is carried by the name itself.
         *
         * Resume is only safe against the *same* file, and a transport exposes no validator —
         * no ETag, no mtime — so the size is the only evidence available. Encoding it here
         * means a remote file that changed length cannot be resumed into, with no sidecar file
         * to keep in step. **The gap this leaves, stated rather than papered over:** a file
         * edited without changing its length still resumes, splicing old bytes and new. Until a
         * validator exists this catches the common case, and the archive's own central directory
         * catches much of the rest.
         *
         * **The upgrade path, when [RangeTransport] gains a validator (an ETag, an mtime).**
         * Resume must then **fail closed**: a part may be continued only when the stored
         * validator is present *and* matches, and must be discarded otherwise. It must not fall
         * back to matching on length when the validator is missing or unreadable — that is the
         * failure this whole scheme already cannot detect, and falling back would reintroduce
         * it precisely when a better answer had become available. Length stops being evidence
         * the moment something stronger exists; it becomes a coincidence.
         */
        fun partFile(destination: File, total: Long): File =
            File(destination.parentFile, "${destination.name}.$total$PART_SUFFIX")

        /** Removes parts aimed at a different size, so an edited file cannot silt up storage. */
        fun discardStaleParts(destination: File, keep: File) {
            val prefix = "${destination.name}."
            destination.parentFile?.listFiles()?.forEach { candidate ->
                val stale = candidate.name.startsWith(prefix) &&
                    candidate.name.endsWith(PART_SUFFIX) &&
                    candidate.name != keep.name
                if (stale) candidate.delete()
            }
        }
    }
}
