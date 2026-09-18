package com.absolutex.remote.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Robolectric provides the real org.json on the JVM (see KomgaClientTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String = "sync_queue.preferences_pb") = File(tmp.root, name)

    private fun open(file: File): Pair<SyncQueue, Job> {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        return SyncQueue(store) to job
    }

    private fun push(server: String = "s1", book: String = "b", updatedAt: Long = 100L) =
        PendingPush(server, book, pageIndex = 5, pageCount = 24, updatedAt = updatedAt)

    @Test fun `empty queue has nothing due`() = runTest {
        val (queue, job) = open(file())
        assertTrue(queue.due(1_000L).isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `enqueue replaces the same book and survives reopen`() = runTest {
        val f = file()
        val (first, firstJob) = open(f)
        first.enqueue(push(updatedAt = 100L))
        first.enqueue(push(updatedAt = 200L))
        assertEquals(200L, first.due(0L).single().updatedAt)
        firstJob.cancelAndJoin()

        val (reopened, job) = open(f)
        assertEquals(1, reopened.due(0L).size)
        job.cancelAndJoin()
    }

    @Test fun `failure backs off and success removes`() = runTest {
        val (queue, job) = open(file())
        queue.enqueue(push())
        queue.recordFailure(push(), 1_000L, "500")
        val parked = queue.due(1_000L)
        assertTrue(parked.isEmpty())
        val later = queue.due(1_000L + BackoffPolicy.delayFor(1))
        assertEquals(1, later.size)
        assertEquals(1, later[0].attempts)
        assertEquals("500", later[0].lastError)
        queue.remove("s1", "b")
        assertTrue(queue.due(Long.MAX_VALUE).isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `backoff is bounded no matter how long the server stays down`() {
        var previous = 0L
        for (attempts in 0..100) {
            val delay = BackoffPolicy.delayFor(attempts)
            assertTrue(delay >= previous)
            assertTrue(delay <= BackoffPolicy.MAX_DELAY_MS)
            previous = delay
        }
        assertEquals(BackoffPolicy.MAX_DELAY_MS, BackoffPolicy.delayFor(1_000_000))
    }
}
