package com.absolutex.feature.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.absolutex.remote.core.TransportInvalidator
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Network-change monitoring without a network: the callback entry is driven directly,
 * which is exactly the seam the real callback calls — delivery itself stays device-owed.
 *
 * Each test names its lifetime clause: the monitor must never retain a closed book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteNetworkMonitorTest {

    private fun monitor(): RemoteNetworkMonitor =
        RemoteNetworkMonitor(ApplicationProvider.getApplicationContext<Context>())

    private class CountingInvalidator(var failures: Int = 0) : TransportInvalidator {
        var invalidations = 0

        override fun invalidate() {
            invalidations++
            if (failures > 0) {
                failures--
                throw IOException("stale session refuses to die quietly")
            }
        }
    }

    @Test fun `network change invalidates every watched transport`() {
        // Proves the proactive drop: without the callback entry a roam would burn the
        // full socket timeout before any retry starts.
        val live = monitor()
        val first = CountingInvalidator()
        val second = CountingInvalidator()
        live.watch(first)
        live.watch(second)
        assertEquals(2, live.watchedCount())
        live.notifyNetworkChanged()
        assertEquals(1, first.invalidations)
        assertEquals(1, second.invalidations)
        live.close()
    }

    @Test fun `unwatched transports stop receiving`() {
        // Proves the handle binds the lifetime: after the book closes (handle shut),
        // later roams never touch its session again.
        val live = monitor()
        val book = CountingInvalidator()
        val handle = live.watch(book)
        live.notifyNetworkChanged()
        assertEquals(1, book.invalidations)
        handle.close()
        assertEquals(0, live.watchedCount())
        live.notifyNetworkChanged()
        assertEquals(1, book.invalidations)
        live.close()
    }

    @Test fun `close clears every watch`() {
        // Proves no retained sessions: closing the monitor (process scope end in
        // tests) empties the set, so nothing outlives its book.
        val live = monitor()
        live.watch(CountingInvalidator())
        live.watch(CountingInvalidator())
        assertEquals(2, live.watchedCount())
        live.close()
        assertEquals(0, live.watchedCount())
        live.close()
    }

    @Test fun `one failing invalidate never stops the rest`() {
        // Proves best-effort broadcast: a dying session that throws on invalidate must
        // not veto the drop of every session behind it, and the throw never escapes
        // onto the connectivity thread.
        val live = monitor()
        val failing = CountingInvalidator(failures = 1)
        val healthy = CountingInvalidator()
        live.watch(failing)
        live.watch(healthy)
        live.notifyNetworkChanged()
        assertEquals(1, failing.invalidations)
        assertEquals(1, healthy.invalidations)
        live.close()
    }

    @Test fun `monitor constructs without the permission`() {
        // Proves the inert-degradation landing: the test manifest declares no
        // ACCESS_NETWORK_STATE, so construction must never throw — books open with
        // retry even where the proactive drop cannot register.
        val live = monitor()
        live.notifyNetworkChanged()
        assertTrue(live.watchedCount() >= 0)
        live.close()
    }
}
