package com.absolutex.core.thumbnails

import java.io.File

/**
 * File-backed LRU for encoded thumbnails: one file per key under a caller-provided [cacheDir],
 * with size accounting in a versioned JSON journal ([ThumbJournal]) and raw bytes in [ThumbFiles].
 *
 * Reads validate the file length against the journal: a truncated entry is evicted and reads as a
 * miss, never a crash. An unreadable journal wipes and rebuilds rather than crashing cold start.
 *
 * The journal is only rewritten every [PERSIST_EVERY_PUTS] puts (or on [close]), not every put:
 * a thumb write is on the hot path and the journal is a few bytes per entry, so batching the
 * rewrite matters far more than reacting to each individual put. A file this process wrote but
 * never got to flush into the journal is never mistaken for corruption: [loadOrRebuild] always
 * reconciles the loaded journal against the files actually on disk and adopts anything missing,
 * so an unclean kill between flushes only delays that entry showing up in the journal until the
 * cache directory is next opened, never drops it.
 * ponytail: the on-disk journal.json can lag up to [PERSIST_EVERY_PUTS] - 1 puts behind reality
 * while this instance is alive between flushes; fine for this process (reads validate against the
 * live in-memory journal, not the file), but a tool inspecting journal.json directly would see a
 * stale count. Upgrade to a flush-on-idle timer if that ever needs to be current mid-session.
 */
class ThumbDiskCache(
    cacheDir: File,
    private val maxBytes: Long = DISK_CAP_BYTES,
) {
    private val files = ThumbFiles(cacheDir)
    private val lock = Any()
    private var journal: ThumbJournal = ThumbJournal.empty()
    private var putsSincePersist = 0

    init {
        require(maxBytes > 0)
        journal = loadOrRebuild()
    }

    fun get(keyHex: String): ByteArray? = synchronized(lock) {
        if (!entryValidLocked(keyHex)) return null
        return files.readBytes(ThumbKeys.fileNameForKey(keyHex))
    }

    fun put(keyHex: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // The bytes themselves can be hundreds of KB; write them before taking the lock so a slow
        // disk only blocks this caller, not every other get/put/remove on the cache.
        files.writeAtomically(ThumbKeys.fileNameForKey(keyHex), bytes)
        synchronized(lock) {
            journal.put(keyHex, bytes.size.toLong())
            evictLocked(journal)
            // Batched: only every PERSIST_EVERY_PUTS puts rewrites the journal (see class doc).
            putsSincePersist++
            if (putsSincePersist >= PERSIST_EVERY_PUTS) {
                persistLocked()
                putsSincePersist = 0
            }
        }
    }

    fun remove(keyHex: String) = synchronized(lock) {
        files.delete(ThumbKeys.fileNameForKey(keyHex))
        if (journal.remove(keyHex)) {
            persistLocked()
            putsSincePersist = 0
        }
    }

    fun sizeBytes(): Long = synchronized(lock) { journal.totalBytes() }

    fun entryCount(): Int = synchronized(lock) { journal.entryCount() }

    fun clear() = synchronized(lock) {
        journal = ThumbJournal.empty()
        putsSincePersist = 0
        files.wipeManaged()
    }

    /** Flushes any journal state batched up by the put debounce. Safe to call more than once. */
    fun close() = synchronized(lock) {
        if (putsSincePersist > 0) {
            persistLocked()
            putsSincePersist = 0
        }
    }

    private fun entryValidLocked(keyHex: String): Boolean {
        val recorded = journal.sizeOf(keyHex)
        val actual = recorded?.let { files.sizeOf(ThumbKeys.fileNameForKey(keyHex)) }
        if (actual == null || actual != recorded) {
            // Truncated or vanished entries read as a miss: storage can die mid-write.
            files.delete(ThumbKeys.fileNameForKey(keyHex))
            if (journal.remove(keyHex)) persistLocked()
            return false
        }
        return true
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
        // A present-but-unreadable journal is corrupt or a foreign version: wipe it and start over.
        if (parsed == null && text != null) files.wipeManaged()
        val base = parsed ?: ThumbJournal.empty()
        // A missing/wiped journal adopts every surviving entry file; a parsed-but-stale one (the
        // debounce in put() can leave one behind) adopts only what it doesn't already know about.
        var adopted = false
        for ((name, size) in files.thumbEntries()) {
            val key = name.removeSuffix(ThumbKeys.FILE_SUFFIX)
            if (base.sizeOf(key) == null) {
                base.put(key, size)
                adopted = true
            }
        }
        if (parsed == null || adopted) {
            evictLocked(base)
            journal = base
            persistLocked()
        }
        return base
    }

    companion object {
        // 512 MiB bounds every book's variants together; disk is a convenience that re-derives
        // cheaply, so it never grows past this.
        const val DISK_CAP_BYTES = 512L * 1024 * 1024
        // How many puts batch up before the journal is rewritten; see the class doc's ponytail note.
        const val PERSIST_EVERY_PUTS = 8
    }
}
