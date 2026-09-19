package com.absolutex.remote.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The skew correction and its HTTP plumbing, without a network. */
class ServerClockTest {

    @Test fun `unknown servers convert by identity`() {
        val clock = ServerClock()
        assertEquals(0L, clock.offsetMs("srv"))
        assertEquals(1_700_000_000_000L, clock.toClientTime("srv", 1_700_000_000_000L))
    }

    @Test fun `a dated response sets the offset in either direction`() {
        val clock = ServerClock()
        // Server clock two hours behind ours: server stamps shift forward by two hours.
        clock.noteServerDate("srv", serverDateMs = 1_000_000L, nowMs = 8_200_000L)
        assertEquals(-7_200_000L, clock.offsetMs("srv"))
        assertEquals(8_200_000L, clock.toClientTime("srv", 1_000_000L))
        // And two hours ahead: stamps shift back by two hours.
        clock.noteServerDate("srv", serverDateMs = 8_200_000L, nowMs = 1_000_000L)
        assertEquals(7_200_000L, clock.offsetMs("srv"))
        assertEquals(1_000_000L, clock.toClientTime("srv", 8_200_000L))
    }

    @Test fun `the newest observation wins`() {
        val clock = ServerClock()
        clock.noteServerDate("srv", serverDateMs = 1_000_000L, nowMs = 8_200_000L)
        clock.noteServerDate("srv", serverDateMs = 5_000_000L, nowMs = 5_001_000L)
        assertEquals(-1_000L, clock.offsetMs("srv"))
    }

    @Test fun `offsets are per server`() {
        val clock = ServerClock()
        clock.noteServerDate("a", serverDateMs = 1_000_000L, nowMs = 8_200_000L)
        assertEquals(0L, clock.offsetMs("b"))
        assertEquals(1_000_000L, clock.toClientTime("b", 1_000_000L))
    }

    @Test fun `decorator feeds response dates into the clock`() {
        val clock = ServerClock()
        val inner = FakeHttpCall()
        inner.enqueue(HttpResponse(200, "{}", serverDateMs = 1_000_000L))
        inner.enqueueBytes(HttpBytesResponse(200, ByteArray(1), serverDateMs = 2_000_000L))
        val call = inner.withClock("srv", clock)
        call.request("GET", "https://nas/x", emptyMap(), null)
        // First observation sticks until the second response lands.
        val now = System.currentTimeMillis().toDouble()
        assertEquals(1_000_000.0 - now, clock.offsetMs("srv").toDouble(), 60_000.0)
        call.requestBytes("GET", "https://nas/y", emptyMap())
        val later = System.currentTimeMillis().toDouble()
        assertEquals(2_000_000.0 - later, clock.offsetMs("srv").toDouble(), 60_000.0)
    }

    @Test fun `dateless responses leave the clock alone`() {
        val clock = ServerClock()
        val inner = FakeHttpCall()
        inner.enqueue(HttpResponse(200, "{}"))
        inner.withClock("srv", clock).request("GET", "https://nas/x", emptyMap(), null)
        assertEquals(0L, clock.offsetMs("srv"))
    }

    @Test fun `transport failures still surface past the decorator`() {
        val clock = ServerClock()
        try {
            FakeHttpCall().withClock("srv", clock).request("GET", "https://nas/x", emptyMap(), null)
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("no queued") == true)
        }
        assertEquals(0L, clock.offsetMs("srv"))
    }

    @Test fun `decision tolerance absorbs noise but keeps order`() {
        val local = SyncProgress("b", 5, 24, 10_000L)
        val remote = SyncProgress("b", 9, 24, 12_000L)
        // Two seconds apart: noise, not an ordering — with tolerance the phone holds.
        assertEquals(SyncDecision.IN_SYNC, syncDecision(local, remote, SYNC_TOLERANCE_MS))
        assertEquals(SyncDecision.PULL, syncDecision(local, remote))
        // Past the band the order stands, both directions.
        val older = SyncProgress("b", 1, 24, 10_000L - SYNC_TOLERANCE_MS - 1L)
        val newer = SyncProgress("b", 9, 24, 10_000L + SYNC_TOLERANCE_MS + 1L)
        assertEquals(SyncDecision.PUSH, syncDecision(local, older, SYNC_TOLERANCE_MS))
        assertEquals(SyncDecision.PULL, syncDecision(local, newer, SYNC_TOLERANCE_MS))
    }

    @Test fun `null remote still pushes under tolerance`() {
        val local = SyncProgress("b", 5, 24, 10_000L)
        assertEquals(SyncDecision.PUSH, syncDecision(local, null, SYNC_TOLERANCE_MS))
    }
}
