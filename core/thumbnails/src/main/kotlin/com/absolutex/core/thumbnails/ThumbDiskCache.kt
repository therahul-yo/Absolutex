package com.absolutex.core.thumbnails

import java.io.File

/**
 * File-backed LRU for encoded thumbnails: one file per key under a caller-provided [cacheDir],
 * with size accounting in a versioned JSON journal ([ThumbJournal]) and raw bytes in [ThumbFiles].
 *
 * Reads validate the file length against the journal: a truncated entry is evicted and reads as a
 * miss, never a crash. An unreadable journal wipes and rebuilds rather than crashing cold start.
 */
class ThumbDiskCache(
    cacheDir: File,
    private val maxBytes: Long = DISK_CAP_BYTES,
) {
    private val files = ThumbFiles(cacheDir)
    private val lock = Any()
    private var journal: ThumbJournal = ThumbJournal.empty()

    init {
        require(maxBytes > 0)
        journal = loadOrRebuild()
    }

    fun get(keyHex: String): ByteArray? = synchronized(lock) {
        if (!entryValidLocked(keyHex)) return null
        return files.readBytes(ThumbKeys.fileNameForKey(keyHex))
    }

    fun put(keyHex: String, bytes: ByteArray): Unit = synchronized(lock) {
        if (bytes.isEmpty()) return
        files.writeAtomically(ThumbKeys.fileNameForKey(keyHex), bytes)
        journal.put(keyHex, bytes.size.toLong())
        evictLocked(journal)
        persistLocked()
    }

    fun remove(keyHex: String) = synchronized(lock) {
        files.delete(ThumbKeys.fileNameForKey(keyHex))
        if (journal.remove(keyHex)) persistLocked()
    }

    fun sizeBytes(): Long = synchronized(lock) { journal.totalBytes() }

    fun entryCount(): Int = synchronized(lock) { journal.entryCount() }

    fun clear() = synchronized(lock) {
        journal = ThumbJournal.empty()
        files.wipeManaged()
    }

    private fun entryValidLocked(keyHex: String): Boolean {
        val recorded = journal.sizeOf(keyHex)
        val actual = recorded?.let { files.sizeOf(ThumbKeys.fileNameForKey(keyHex)) }
        if (actual == null || actual != recorded) {
            // Truncated or vanished entries read as a miss: storage can die mid-write.
            return evictKeyLocked(keyHex)
        }
        return true
    }

    private fun evictKeyLocked(keyHex: String): Boolean {
        files.delete(ThumbKeys.fileNameForKey(keyHex))
        if (journal.remove(keyHex)) persistLocked()
        return false
    }

    private fun evictLocked(target: ThumbJournal) {
        for (victim in target.keysToEvict(maxBytes)) {
            files.delete(ThumbKeys.fileNameForKey(victim))
            target.remove(victim)
        }
    }

    private fun persistLocked() {
        files.writeAtomically(ThumbJournal.JOURNAL_FILE, journal.serialize().toByteArray())
    }

    private fun loadOrRebuild(): ThumbJournal {
        val text = files.readText(ThumbJournal.JOURNAL_FILE)
        val parsed = text?.let { ThumbJournal.parse(it) }
        if (parsed != null) return parsed
        // A present-but-unreadable journal is corrupt or a foreign version: wipe it and start over.
        // A missing journal just adopts whatever entry files survived, then enforces the cap.
        if (text != null) files.wipeManaged()
        val fresh = ThumbJournal.empty()
        for ((name, size) in files.thumbEntries()) {
            fresh.put(name.removeSuffix(ThumbKeys.FILE_SUFFIX), size)
        }
        evictLocked(fresh)
        journal = fresh
        persistLocked()
        return fresh
    }

    companion object {
        // 512 MiB bounds every book's variants together; disk is a convenience that re-derives
        // cheaply, so it never grows past this.
        const val DISK_CAP_BYTES = 512L * 1024 * 1024
    }
}
