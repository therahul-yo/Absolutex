package com.absolutex.remote.sync

/**
 * Moments when a sync may run. Declared for later consumption by the app/reader layers — this
 * module never observes app state itself (reader/app files are owned by the lead).
 */
enum class SyncTrigger {
    /** User tapped sync/retry. */
    MANUAL,

    /** Library browse or pull-to-refresh completed. */
    BROWSE_REFRESH,

    /** A book was opened or closed. */
    OPEN_CLOSE,

    /** OS-scheduled background work. */
    BACKGROUND,
}

/** Outcome of comparing one local position against its remote twin. */
enum class SyncDecision {
    /** Local is newer (or remote is missing): upload local. */
    PUSH,

    /** Remote is newer: adopt remote (applied by later reader wiring, never here). */
    PULL,

    /** Same timestamp: keep local, write nothing. */
    IN_SYNC,
}

/**
 * Pure last-write-wins decision, matching local Progress semantics (updatedAt decides).
 *
 * Tie keeps local: equal stamps mean neither side moved, so IN_SYNC writes nothing anywhere.
 *
 * Clock-skew caution: [SyncProgress.updatedAt] mixes the device clock with server clocks, so a
 * millisecond margin is not a trustworthy ordering — it only breaks ties consistently. Callers
 * must not treat a 1 ms gap as a real signal, and tests use fixed stamps, never wall-clock.
 */
fun syncDecision(local: SyncProgress, remote: SyncProgress?): SyncDecision {
    if (remote == null) return SyncDecision.PUSH
    if (local.updatedAt > remote.updatedAt) return SyncDecision.PUSH
    if (local.updatedAt < remote.updatedAt) return SyncDecision.PULL
    return SyncDecision.IN_SYNC
}

/** Remote side of one book's position; fakes drive the engine in tests. */
interface RemoteStore {
    fun load(bookId: String): SyncProgress?
    fun store(progress: SyncProgress)
}

/**
 * Pulls the remote position, decides with [syncDecision], and pushes if and only if local won.
 * PULL never writes locally here — applying remote positions needs the reader/DB wiring that
 * lands with the lead's files.
 */
class SyncEngine(private val remote: RemoteStore) {
    fun syncBook(local: SyncProgress): SyncDecision {
        val decision = syncDecision(local, remote.load(local.bookId))
        if (decision == SyncDecision.PUSH) remote.store(local)
        return decision
    }
}
