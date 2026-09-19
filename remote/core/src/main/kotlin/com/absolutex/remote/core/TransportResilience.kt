package com.absolutex.remote.core

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Refused credentials at logon: bad password, unknown user, refused FTP login.
 *
 * Never retried, by type rather than by message: every retried logon is another NTLM
 * or FTP password attempt against the NAS, and a handful of queued pages retrying a
 * stale password is exactly how accounts get locked. Transports throw this instead of
 * a bare IOException so [isTransient] refuses it even when the message is unfamiliar.
 */
class TransportAuthException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The password changed on the NAS mid-session: a previously good session is now refused.
 *
 * Distinct from [TransportAuthException] (wrong from the start) because the fix differs:
 * this means "sign in again", never "retry" and never "unreachable". It never enters the
 * retry loop, and it propagates to the reader unwrapped so agent3's servers UI can match
 * it by type ([serverId] routes the sign-in prompt when the transport knows it).
 */
class CredentialExpiredException(
    message: String,
    cause: Throwable? = null,
    val serverId: String? = null,
) : IOException(message, cause)

/**
 * A definitive failure: missing file, denied access, malformed response.
 *
 * Retrying cannot help — the server already gave its final answer — so these skip the
 * retry loop entirely and surface immediately. Transports map definitive protocol
 * statuses here (SMB not-found, FTP 5xx) instead of string-matching later.
 */
class TransportPermanentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A dead-socket-class failure observed by the transport: reset connection, dropped
 * session, refused data connection, EOF mid-stream.
 *
 * Thrown by the transport that saw the failure, which is the only place with enough
 * evidence to tell "the socket died" from "the file is not there". [isTransient]
 * trusts it unconditionally; the retry loop converts exhaustion into
 * [TransientExhaustedException], so this type never reaches the reader itself.
 */
class TransientTransportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Bounded retries used up on a transient failure.
 *
 * The stable typed error the reader can rely on (see [CoreComicSource]): [cause] is the
 * last failure, earlier attempts are suppressed onto it in order, so no evidence is
 * lost. Retrying the same call later starts a fresh budget — the network may have healed.
 */
class TransientExhaustedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Whether a transport failure is worth retrying with backoff.
 *
 * Transient means the next attempt could plausibly succeed: timeouts, resets, refused
 * sockets, DNS blips, EOF mid-stream. Everything else — auth refusal (lockout risk),
 * credential rotation (needs sign-in), definitive answers, interrupts, TLS failures —
 * returns false, so the real error surfaces immediately instead of after seconds of
 * pointless backoff. Unknown bare messages default to false for the same reason: a
 * failure the classifier does not recognise is evidence, not noise.
 *
 * Shared with the probe taxonomy ([mapProbeFailure] in `:remote:sync` answers a
 * different question — what to show — while this answers whether to retry); the two
 * must agree that auth/TLS failures are never transient.
 */
fun isTransient(error: IOException): Boolean {
    // SocketTimeoutException extends InterruptedIOException, so it must be carved out
    // before the definitive check below: a read timeout is the canonical transient
    // failure, while a bare InterruptedIOException is cancellation.
    if (error is SocketTimeoutException) return true
    // The transport's explicit verdict wins over every message marker: a
    // TransientTransportException carrying the word "closed" in its message is still
    // the dead socket the transport saw, not a definitive answer. (Bare message-only
    // errors still go through the marker lists below.)
    if (error is TransientTransportException) return true
    if (isDefinitiveType(error)) return false
    if (mentionsDefinitive(error)) return false
    // Transports wrap: SMBJ surfaces dead sockets as unchecked failures the transport
    // re-wraps in IOException, so a typed transient cause anywhere in the chain counts
    // even when the outer error is a plain IOException.
    if (hasTransientCause(error)) return true
    return mentionsTransient(error)
}

private fun isDefinitiveType(error: IOException): Boolean =
    error is TransportAuthException || error is CredentialExpiredException ||
        error is TransportPermanentException || error is TransientExhaustedException ||
        error is InterruptedIOException || error is FileNotFoundException ||
        hasCause<SSLException>(error) || hasCause<InterruptedException>(error)

private fun errorMessages(error: Throwable): Sequence<String> = sequence {
    var cause: Throwable? = error
    while (cause != null) {
        cause.message?.let { yield(it.lowercase()) }
        cause = cause.cause
    }
}

private fun mentionsDefinitive(error: IOException): Boolean =
    errorMessages(error).any { message -> DEFINITIVE_MARKERS.any { message.contains(it) } }

private fun mentionsTransient(error: IOException): Boolean =
    errorMessages(error).any { message -> TRANSIENT_MARKERS.any { message.contains(it) } }

private inline fun <reified T : Throwable> hasCause(error: Throwable): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is T) return true
        cause = cause.cause
    }
    return false
}

private fun hasTransientCause(error: IOException): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (isTimeoutOrReset(cause) || isDnsOrEof(cause)) return true
        cause = cause.cause
    }
    return false
}

private fun isTimeoutOrReset(cause: Throwable): Boolean =
    cause is SocketTimeoutException || cause is SocketException

private fun isDnsOrEof(cause: Throwable): Boolean =
    cause is UnknownHostException || cause is EOFException

/**
 * Markers that veto a retry wherever they appear in the cause chain. Checked before the
 * transient markers, so "FTP login refused" (contains "refused") still refuses: the
 * negative list always wins, because a missed veto locks NAS accounts or delays the
 * real error, while a missed retry only costs one immediate failure the reader relays.
 */
private val DEFINITIVE_MARKERS = listOf(
    "credential",
    "auth",
    "login",
    "password",
    "not found",
    "no such",
    "past end",
    "closed",
    "interrupt",
    "too large",
    "truncated",
    "malformed",
    "denied",
    "unreadable",
)

/**
 * Markers for bare IOExceptions whose type says nothing (transport internals, Commons
 * Net, wrapped runtime failures). Typed causes ([SocketException] and friends) never
 * reach this list — it exists for message-only evidence like "short read at 0".
 */
private val TRANSIENT_MARKERS = listOf(
    "timed out",
    "timeout",
    "connection reset",
    "reset by peer",
    "broken pipe",
    "eof",
    "short read",
    "short ftp read",
    "did not complete",
    "connection abort",
    "try again",
    "invalidated",
    "refused",
)

/**
 * Bounded exponential backoff: attempts bound, waits capped, budgets predictable.
 *
 * Defaults (3 attempts, 200ms doubling to a 2s cap) add at most 600ms to a failing
 * read — under the reader's page expectations and far under the 30s socket timeout a
 * network change would otherwise burn. SMB overrides to 2 attempts to preserve its
 * reconnect-once ceiling; the per-module KDoc says why.
 */
data class RetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val multiplier: Double = DEFAULT_MULTIPLIER,
) {
    init {
        require(maxAttempts >= 1) { "attempts must bound at least one try: $maxAttempts" }
        require(initialDelayMs >= 0) { "negative initial delay: $initialDelayMs" }
        require(maxDelayMs >= initialDelayMs) { "cap below initial delay: $maxDelayMs" }
        require(multiplier >= 1.0) { "backoff must not shrink: $multiplier" }
    }

    /**
     * Wait after failed attempt [failedAttempt] (1-based): exponential, capped. Pure —
     * the blocking transports and the suspending helper share this, so every module
     * waits the same schedule and unit tests pin it without sleeping.
     */
    fun delayForAttempt(failedAttempt: Int): Long {
        require(failedAttempt >= 1) { "attempts are 1-based: $failedAttempt" }
        var wait = initialDelayMs
        repeat(failedAttempt - 1) { wait = minOf((wait * multiplier).toLong(), maxDelayMs) }
        return minOf(wait, maxDelayMs)
    }

    /**
     * Blocking variant of [delayForAttempt] for the transports, which are blocking APIs
     * (SMBJ and Commons Net block; the decode pool calls them off the main thread).
     * The interrupt is the cancellation channel there: status is restored and an
     * [InterruptedIOException] (never transient, never retried) escapes, so closing the
     * book mid-backoff stops the retry instead of sleeping through it.
     */
    fun sleepBeforeRetry(failedAttempt: Int) {
        try {
            Thread.sleep(delayForAttempt(failedAttempt))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("retry backoff interrupted").apply { initCause(e) }
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_INITIAL_DELAY_MS = 200L
        const val DEFAULT_MAX_DELAY_MS = 2_000L
        const val DEFAULT_MULTIPLIER = 2.0
    }
}

/**
 * Bounded retry with backoff for suspending callers: transient failures only, waits via
 * [delay] (cancellable and virtual under `runTest`), attempts bounded by [policy].
 *
 * - [coroutineContext.ensureActive] runs before every attempt, so a cancelled scope
 *   never starts another try; [delay] throws on cancellation mid-backoff, and
 *   [CancellationException] is never caught (it is not an IOException), so cancelling
 *   mid-backoff stops everything.
 * - Non-transient failures propagate unwrapped — especially
 *   [CredentialExpiredException], which the reader matches by type for sign-in-again.
 * - Exhaustion throws [TransientExhaustedException] carrying the last failure as cause
 *   and every earlier attempt suppressed in order.
 * - [onRetry] runs before each wait for reconnect-and-resume hooks; it sees the
 *   1-based failed attempt and its error.
 */
@Throws(IOException::class)
suspend fun <T> withBoundedRetry(
    policy: RetryPolicy = RetryPolicy(),
    onRetry: suspend (failedAttempt: Int, error: IOException) -> Unit = { _, _ -> },
    block: suspend (attempt: Int) -> T,
): T {
    val prior = ArrayList<IOException>(policy.maxAttempts)
    var attempt = 0
    while (true) {
        coroutineContext.ensureActive()
        attempt++
        try {
            return block(attempt)
        } catch (e: IOException) {
            if (!isTransient(e)) {
                prior.forEach(e::addSuppressed)
                throw e
            }
            if (attempt >= policy.maxAttempts) {
                throw TransientExhaustedException("transient failure after $attempt attempts", e)
                    .also { exhausted -> prior.forEach(exhausted::addSuppressed) }
            }
            prior += e
            onRetry(attempt, e)
            delay(policy.delayForAttempt(attempt))
        }
    }
}

/**
 * Network-change hook: Wi-Fi→cellular or a VPN toggle invalidates sockets with no
 * immediate error, and waiting out the 30s read timeout is the wrong recovery.
 *
 * Implemented by the transports (drop the stale session; the next read reconnects) and
 * driven by the Android `NetworkCallback` where Context exists (see
 * `RemoteNetworkMonitor` in `:feature:remote`). Lives here so transports stay testable
 * without Android: unit tests call [invalidate] directly. Never throws, never blocks
 * long — callbacks fire on a connectivity thread and must not stall it.
 */
fun interface TransportInvalidator {
    fun invalidate()
}
