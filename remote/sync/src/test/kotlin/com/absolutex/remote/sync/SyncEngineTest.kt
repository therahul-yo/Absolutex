package com.absolutex.remote.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure decision tests use fixed stamps only — never the wall clock. */
class SyncEngineTest {

    private fun progress(bookId: String = "book", updatedAt: Long = 1_700_000_000_000L) =
        SyncProgress(bookId = bookId, pageIndex = 5, pageCount = 24, updatedAt = updatedAt)

    @Test fun localNewerPushes() {
        assertEquals(SyncDecision.PUSH, syncDecision(progress(updatedAt = 200L), progress(updatedAt = 100L)))
    }

    @Test fun remoteNewerPulls() {
        assertEquals(SyncDecision.PULL, syncDecision(progress(updatedAt = 100L), progress(updatedAt = 200L)))
    }

    @Test fun tieKeepsLocal() {
        assertEquals(SyncDecision.IN_SYNC, syncDecision(progress(updatedAt = 100L), progress(updatedAt = 100L)))
    }

    @Test fun missingRemotePushes() {
        assertEquals(SyncDecision.PUSH, syncDecision(progress(), null))
    }

    @Test fun oneMillisecondSkewFlipsDecision() {
        // Documents the skew caution in syncDecision: sub-second margins still order.
        assertEquals(SyncDecision.PULL, syncDecision(progress(updatedAt = 1_000L), progress(updatedAt = 1_001L)))
    }

    @Test fun toleranceBandReadsSmallGapsAsTies() {
        val local = progress(updatedAt = 50_000L)
        assertEquals(SyncDecision.IN_SYNC, syncDecision(local, progress(updatedAt = 51_000L), SYNC_TOLERANCE_MS))
        assertEquals(SyncDecision.IN_SYNC, syncDecision(local, progress(updatedAt = 49_000L), SYNC_TOLERANCE_MS))
    }

    @Test fun toleranceBandKeepsLargeGapsOrdered() {
        val local = progress(updatedAt = 50_000L)
        val older = progress(updatedAt = 50_000L - SYNC_TOLERANCE_MS - 1L)
        val newer = progress(updatedAt = 50_000L + SYNC_TOLERANCE_MS + 1L)
        assertEquals(SyncDecision.PUSH, syncDecision(local, older, SYNC_TOLERANCE_MS))
        assertEquals(SyncDecision.PULL, syncDecision(local, newer, SYNC_TOLERANCE_MS))
    }

    @Test fun enginePushesIfAndOnlyIfLocalNewer() {
        val stored = mutableListOf<SyncProgress>()
        val engine = SyncEngine(object : RemoteStore {
            override fun load(bookId: String): SyncProgress? = progress(updatedAt = 100L)
            override fun store(progress: SyncProgress): Unit {
                stored += progress
            }
        })
        assertEquals(SyncDecision.PUSH, engine.syncBook(progress(updatedAt = 200L)))
        assertEquals(1, stored.size)
        assertEquals(SyncDecision.PULL, engine.syncBook(progress(updatedAt = 50L)))
        assertEquals(1, stored.size)
        assertEquals(SyncDecision.IN_SYNC, engine.syncBook(progress(updatedAt = 100L)))
        assertEquals(1, stored.size)
    }

    @Test fun engineTreatsMissingRemoteAsPush() {
        var stored: SyncProgress? = null
        val engine = SyncEngine(object : RemoteStore {
            override fun load(bookId: String): SyncProgress? = null
            override fun store(progress: SyncProgress): Unit {
                stored = progress
            }
        })
        assertEquals(SyncDecision.PUSH, engine.syncBook(progress()))
        assertEquals(progress(), stored)
    }
}
