package com.absolutex.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins the one property [runCatchingCancellable] exists for: cancellation propagates, ordinary
 * failure is captured. Reverting the `onFailure` rethrow in the helper turns the first test red
 * — the job would complete normally and the post-helper line would run.
 */
class RunCatchingCancellableTest {

    @Test
    fun `a cancelled block propagates, so the job ends cancelled and the next line never runs`() = runTest {
        var afterHelper = false
        val job = launch {
            runCatchingCancellable { awaitCancellation() }
            afterHelper = true
        }
        // Let the launch reach awaitCancellation, then cancel the scan it stands in for.
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue("the job must end cancelled, not completed", job.isCancelled)
        assertFalse("code after the helper must not run once cancelled", afterHelper)
    }

    @Test
    fun `an ordinary failure is captured, not thrown`() = runTest {
        val result = runCatchingCancellable { throw IOException("disk gone") }
        assertTrue("IOException must come back as Result.failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `a value passes through`() = runTest {
        val result = runCatchingCancellable { 42 }
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == 42)
    }
}
