package com.absolutex.remote.core

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared retry policy: classification, budgets, the suspend helper.
 *
 * Every behavioural test carries its mutation note — what it proves and what it fails
 * without — because the lead reviews these as the retry-logic proof.
 */
class TransportResilienceTest {

    @Test fun `socket errors are transient`() {
        assertTrue(isTransient(IOException("x", SocketTimeoutException("timed out"))))
        assertTrue(isTransient(IOException("x", SocketException("connection reset"))))
        assertTrue(isTransient(IOException("x", UnknownHostException("nas"))))
        assertTrue(isTransient(IOException("x", EOFException("eof mid-stream"))))
    }

    @Test fun `typed transport failures classify by construction`() {
        assertFalse(isTransient(TransportAuthException("bad password")))
        assertFalse(isTransient(CredentialExpiredException("changed")))
        assertFalse(isTransient(TransportPermanentException("gone")))
        assertFalse(isTransient(TransientExhaustedException("used up", IOException("x"))))
        assertTrue(isTransient(TransientTransportException("reset")))
    }

    @Test fun `interrupts tls and missing files never retry`() {
        assertFalse(isTransient(InterruptedIOException("backoff cut")))
        assertFalse(isTransient(FileNotFoundException("no such file")))
        assertFalse(isTransient(IOException("handshake", SSLException("alert"))))
        // An in-flight read cut by book close arrives as a closed socket: retrying it
        // would resurrect a dead book's read, so "closed" vetoes even typed sockets.
        assertFalse(isTransient(SocketException("Socket closed")))
    }

    @Test fun `negative message markers win over transient ones`() {
        // Contains "refused" (transient marker) but "login" vetoes first: a refused
        // logon retried is a NAS lockout, so the negative list always wins.
        assertFalse(isTransient(IOException("FTP login refused: ftp://nas")))
        assertFalse(isTransient(IOException("smb authentication failed")))
        assertFalse(isTransient(IOException("short read past end: Gordian knot")))
    }

    @Test fun `transport short reads are transient by message`() {
        assertTrue(isTransient(IOException("short read at 0 (0 of 16 bytes)")))
        assertTrue(isTransient(IOException("short FTP read: /b.cbz at 4000 (0 of 500)")))
        assertTrue(isTransient(IOException("smb read failed", RuntimeException("session invalidated"))))
    }

    @Test fun `unknown bare messages default to non-transient`() {
        // A failure the classifier does not recognise is evidence, not noise: surfacing
        // it immediately beats delaying the real error behind pointless backoff.
        assertFalse(isTransient(IOException("weird vendor reply 999")))
    }

    @Test fun `backoff doubles to the cap`() {
        val policy = RetryPolicy()
        assertEquals(200L, policy.delayForAttempt(1))
        assertEquals(400L, policy.delayForAttempt(2))
        assertEquals(800L, policy.delayForAttempt(3))
        assertEquals(2_000L, policy.delayForAttempt(10))
    }

    @Test fun `policy rejects nonsense`() {
        for (policy in listOf(
            { RetryPolicy(maxAttempts = 0) },
            { RetryPolicy(initialDelayMs = -1) },
            { RetryPolicy(initialDelayMs = 500, maxDelayMs = 100) },
            { RetryPolicy(multiplier = 0.5) },
        )) {
            try {
                policy()
                throw AssertionError("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.isNotEmpty() == true)
            }
        }
    }

    @Test fun `fail-transient-twice-then-succeed takes exactly three attempts`() = runTest {
        // Proves the retry loop exists: without it the first throw escapes and both
        // assertions fail (attempts == 1, runTest rethrows the timeout).
        var attempts = 0
        val got = withBoundedRetry {
            attempts++
            if (attempts < 3) throw SocketTimeoutException("timed out")
            "ok"
        }
        assertEquals("ok", got)
        assertEquals(3, attempts)
        // Virtual time: delay() advances the test clock, never a wall clock.
        assertEquals(600L, testScheduler.currentTime)
    }

    @Test fun `always-transient exhausts as a typed error with the evidence chain`() = runTest {
        // Proves the bound: without it this loop never terminates (and the attempts
        // assertion pins the bound at exactly three, not "a few").
        var attempts = 0
        try {
            withBoundedRetry { attempts++ ; throw SocketTimeoutException("timed out") }
            throw AssertionError("expected TransientExhaustedException")
        } catch (expected: TransientExhaustedException) {
            assertEquals(3, attempts)
            assertTrue(expected.cause is SocketTimeoutException)
            assertEquals(2, expected.suppressed.size)
        }
    }

    @Test fun `auth failure is never retried and propagates unwrapped`() = runTest {
        // Proves the lockout guard: a retried bad password would show attempts == 3 and
        // a virtual-time advance; instead the identical instance escapes immediately.
        val auth = TransportAuthException("bad password")
        var attempts = 0
        try {
            withBoundedRetry {
                attempts++
                throw auth
            }
            throw AssertionError("expected TransportAuthException")
        } catch (expected: TransportAuthException) {
            assertSame(auth, expected)
            assertEquals(1, attempts)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `credential expiry is never retried and escapes by type`() = runTest {
        // Proves the sign-in-again signal survives: wrapping it (even in an exhausted
        // error) would hide it from the reader's `is` check, so identity is asserted.
        val expired = CredentialExpiredException("changed on the NAS", serverId = "nas")
        var attempts = 0
        try {
            withBoundedRetry {
                attempts++
                throw expired
            }
            throw AssertionError("expected CredentialExpiredException")
        } catch (expected: CredentialExpiredException) {
            assertSame(expired, expected)
            assertEquals(1, attempts)
        }
    }

    @Test fun `eof mid-stream then success resumes on the second attempt`() = runTest {
        // Proves the reconnect-and-resume shape at the policy level: the first failure
        // is transient (EOF mid-stream) and the retry completes the read.
        var attempts = 0
        val reconnects = ArrayList<Int>()
        val got = withBoundedRetry(onRetry = { attempt, _ -> reconnects += attempt }) {
            attempts++
            if (attempts == 1) throw TransientTransportException("short read at 0 (0 of 8 bytes)")
            "resumed"
        }
        assertEquals("resumed", got)
        assertEquals(2, attempts)
        assertEquals(listOf(1), reconnects)
    }

    @Test fun `server vanishes and stays gone exhausts without hanging`() = runTest {
        // Proves the vanish shape: every attempt fails transiently, the bound stops the
        // loop, and virtual time advanced only by the two scheduled waits (200 + 400).
        var attempts = 0
        try {
            withBoundedRetry { attempts++ ; throw EOFException("server vanished") }
            throw AssertionError("expected TransientExhaustedException")
        } catch (expected: TransientExhaustedException) {
            assertEquals(3, attempts)
            assertEquals(600L, testScheduler.currentTime)
        }
    }

    @Test fun `interrupt during blocking backoff ends the exchange and restores the flag`() {
        // Proves the blocking bridge preserves the interrupt contract: without the
        // catch, runBlocking lets a raw InterruptedException escape with the flag
        // consumed — not an IOException, so transport drop-on-failure misses it, and
        // the pool thread's cancellation signal is silently lost. Mutation: remove
        // the Thread.currentThread().interrupt() call and the flag assertion goes red.
        Thread.interrupted() // clear: an earlier test must never leak its flag in here
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val flagAfter = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        val worker = Thread {
            try {
                withBoundedRetryBlocking(
                    RetryPolicy(maxAttempts = 3, initialDelayMs = 5_000L, maxDelayMs = 60_000L),
                ) { _: Int ->
                    attempts.incrementAndGet()
                    entered.countDown()
                    throw SocketTimeoutException("read timed out")
                }
            } catch (e: Throwable) {
                failure = e
                flagAfter.set(Thread.interrupted())
            }
        }
        worker.start()
        // Attempt one runs, then parks in the 5 s backoff: interrupt there, so the
        // test proves the wait ends instead of asserting about the attempt. Bounded
        // wait, never a spin: a worker that never reaches its op fails, not hangs.
        assertTrue("blocking op never ran", entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(100)
        worker.interrupt()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertTrue(failure is InterruptedIOException)
        assertTrue((failure as InterruptedIOException).message?.contains("backoff") == true)
        assertTrue(flagAfter.get())
        assertEquals(1, attempts.get())
    }

    @Test fun `cancelling mid-backoff stops everything and the session still closes`() = runTest {
        // Proves waits are cancellable: without delay() this test would need wall-clock
        // sleeps, and without ensureActive a cancelled scope would start attempt two.
        var attempts = 0
        var closes = 0
        val fake = object : RangeTransport {
            override fun sizeBytes(): Long = 1L
            override fun readAt(offset: Long, length: Int): ByteArray {
                attempts++
                throw SocketTimeoutException("timed out")
            }
            override fun close() {
                closes++
            }
        }
        val job = launch {
            try {
                withBoundedRetry { fake.readAt(0, 1) }
            } finally {
                fake.close()
            }
        }
        // Attempt one runs and parks in backoff; cancelling there must end it: no
        // second attempt starts (ensureActive) and the delayed wait never resumes.
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(1, attempts)
        assertEquals(1, closes)
    }
}
