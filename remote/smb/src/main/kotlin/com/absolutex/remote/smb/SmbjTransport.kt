package com.absolutex.remote.smb

import com.absolutex.remote.core.CredentialExpiredException
import com.absolutex.remote.core.RetryPolicy
import com.absolutex.remote.core.TransportAuthException
import com.absolutex.remote.core.TransportInvalidator
import com.absolutex.remote.core.TransportPermanentException
import com.absolutex.remote.core.TransientExhaustedException
import com.absolutex.remote.core.TransientTransportException
import com.absolutex.remote.core.isTransient
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.hierynomus.smbj.share.DiskShare
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.EnumSet
import javax.net.ssl.SSLException

/**
 * SMBJ-backed [SmbTransport]. SMB3 only by default (3.0 through 3.1.1): SMB 2.1 has no
 * encryption, so a dialect that cannot be encrypted cannot be required-encrypted — and
 * SMBJ's dialect enum has no SMB1 constant at all, so SMB1 negotiation is impossible by
 * construction, not by flag. Signatures verified against smbj-0.15.0
 * (javap over the Central jar, 2026-09-15) rather than docs.
 *
 * Two failure policies, split where they belong:
 *
 * - Establishing fails (bad password, unreachable host, refused share): the remembered
 *   error is rethrown without touching the network again. Five queued pages with a stale
 *   password used to mean five leaked connections and five NTLM logons — enough to lock a
 *   NAS or domain account. `close()` resets the latch; asking for the password again is a
 *   new transport.
 * - Reading on an established share fails (NAS reboot, Wi-Fi roam, idle drop): the share
 *   is dropped and the read reconnects once, then the second failure propagates.
 *
 * Blocking by design (SMBJ is a blocking API) — callers must stay off the main thread,
 * same as local archive extraction on DecodeDispatchers.extract.
 *
 * Phase F resilience, underneath the policies above (locks, latch and single-flight
 * are unchanged):
 *
 * - Bounded retry with backoff around reads and stats, transient failures only
 *   ([isTransient]): a dead share drops, waits once, and reconnects — the
 *   reconnect-once ceiling is preserved as a bound of two attempts. Auth refusal,
 *   credential rotation and definitive failures (missing file, denied, malformed)
 *   never enter the loop: retrying a refused password locks NAS accounts.
 * - Failures are typed ([TransportAuthException], [CredentialExpiredException],
 *   [TransportPermanentException], [TransientExhaustedException]) so the reader can
 *   rely on them by type; exhaustion carries the last failure as cause with the
 *   first suppressed, preserving the old second-carries-first evidence shape.
 * - [invalidate] drops a network-changed session without latching, so a Wi-Fi roam
 *   reconnects on the next read instead of waiting out the socket timeout.
 */
class SmbjTransport(
    private val location: SmbLocation,
    private val credentials: SmbCredentialStore,
    private val credentialAlias: String,
    private val connector: SmbConnector = SmbjConnector(location),
    private val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = RECONNECT_ATTEMPTS),
) : SmbTransport, TransportInvalidator {

    private val guard = Any()
    private var connection: SmbConnection? = null
    private var authFailure: IOException? = null
    private var closed = false
    private var establishTask: EstablishTask? = null

    /**
     * Set when an established session is refused with logon-failure: the password
     * changed on the NAS mid-session. The next connect failing the same way then
     * surfaces as rotation (sign in again) rather than a first-time bad password —
     * cleared on any successful logon, so a corrected password heals the transport.
     */
    private var staleCredentials = false

    private fun connectedShare(): SmbConnection {
        while (true) {
            var mine = false
            val task = synchronized(guard) {
                if (closed) throw IOException("transport closed")
                // Fresh instance every time: rethrowing the stored error itself lets the retry
                // path below call second.addSuppressed(first) on one object, which is
                // IllegalArgumentException("Self-suppression not permitted") — not an IOException.
                authFailure?.let { throw IOException(AUTH_LATCH_MESSAGE, it) }
                connection?.let { return it }
                // Single-flight: one thread establishes while the rest wait on its task.
                // Without it N threads on a cold transport run N NTLM logons — and with a
                // bad stored password, N simultaneous failures before the latch is set.
                val inFlight = establishTask
                if (inFlight != null) {
                    inFlight
                } else {
                    mine = true
                    EstablishTask().also { establishTask = it }
                }
            }
            if (mine) {
                finishEstablish(task)
            } else {
                task.awaitEstablished()
            }
        }
    }

    /**
     * The single in-flight logon: off-guard network, under-guard publish. Failure latches
     * (see the class KDoc); only the seam's IOException latches — an unchecked escape from
     * a connector is a contract violation and surfaces immediately, unlatched.
     */
    private fun finishEstablish(task: EstablishTask) {
        var password: CharArray? = null
        try {
            password = storedPassword()
            installEstablished(connector.connect(password))
            synchronized(guard) { staleCredentials = false }
        } catch (e: IOException) {
            val typed = mapConnectFailure(e, location.host, credentialAlias) {
                synchronized(guard) { staleCredentials }
            }
            synchronized(guard) {
                if (authFailure == null) {
                    authFailure = typed
                }
            }
            throw typed
        } finally {
            password?.fill(Char.MIN_VALUE)
            synchronized(guard) {
                if (establishTask === task) establishTask = null
            }
            task.complete()
        }
    }

    /**
     * Publishes a fresh connection under guard. A concurrent establish may have won first,
     * or close() / a latch may have landed mid-logon: in every losing case the spare is
     * closed and the winner (or the failure) stands, so no path swaps a healthy connection
     * out from under its readers. The decision runs under the lock; the loser's close runs
     * outside it — close() is a network round trip (same rule as dropForReconnect/close()).
     */
    private fun installEstablished(established: SmbConnection): SmbConnection {
        var spare: SmbConnection? = null
        var failure: IOException? = null
        var winner: SmbConnection? = null
        synchronized(guard) {
            val latched = authFailure
            val current = connection
            when {
                closed -> {
                    spare = established
                    failure = IOException("transport closed")
                }
                latched != null -> {
                    spare = established
                    failure = IOException(AUTH_LATCH_MESSAGE, latched)
                }
                current != null -> {
                    spare = established
                    winner = current
                }
                else -> connection = established
            }
        }
        spare?.let { runCatching { it.close() } }
        failure?.let { throw it }
        return winner ?: established
    }

    private fun storedPassword(): CharArray {
        try {
            return credentials.retrieve(credentialAlias)
                ?: throw IOException("no stored credentials for $credentialAlias")
        } catch (e: GeneralSecurityException) {
            // A tampered credential file throws here, never IOException: without this latch
            // every queued read would re-decrypt and throw again instead of failing once.
            throw IOException("stored credentials unreadable", e)
        }
    }

    /**
     * Drops the share without latching, but only if [failed] is still the live connection:
     * a second thread's teardown must never close a healthy connection another thread just
     * established. The swap runs under the lock; the close runs outside it, because
     * TREE_DISCONNECT is a network round trip that can stall a half-dead session up to the
     * socket timeout while every other reader waits on the lock. The next read reconnects once.
     */
    private fun dropForReconnect(failed: SmbConnection) {
        val stale = synchronized(guard) {
            if (connection === failed) {
                connection.also { connection = null }
            } else {
                null
            }
        }
        if (stale != null) {
            runCatching { stale.close() }
        }
    }

    override fun sizeBytes(remotePath: String): Long {
        return reconnecting { share ->
            smbReadLength(share, remotePath, credentialAlias) {
                synchronized(guard) { staleCredentials = true }
            }
        }
    }

    override fun readAt(remotePath: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        if (length == 0) return ByteArray(0)
        return reconnecting { share ->
            smbReadOnce(share, remotePath, offset, length, credentialAlias) {
                synchronized(guard) { staleCredentials = true }
            }
        }
    }

    /**
     * One revalidation with backoff — then the failure propagates, typed. The failure
     * may be a dead share, in which case the retry reconnects; auth, rotation and
     * definitive failures never reach the retry (see the class KDoc). The second
     * failure carries the first as suppressed context when it propagates as-is, or
     * the exhaustion error carries the first with the second as cause — no loops, no
     * storms, no lost evidence. An establish failure is not retried at all: there is
     * no share to drop and the retry could only re-hit the latch.
     */
    private inline fun <T> reconnecting(op: (SmbConnection) -> T): T {
        val share = connectedShare()
        try {
            return op(share)
        } catch (first: IOException) {
            if (!isTransient(first)) {
                // Rotation killed the session even though nothing will retry it: drop
                // it so the next read reconnects (and re-reports) instead of reading
                // from a dead session. Anything else leaves a healthy share alone.
                if (first is CredentialExpiredException) dropForReconnect(share)
                throw first
            }
            dropForReconnect(share)
            // Backoff before the single reconnect: a rebooting NAS answers instantly
            // with reset/refused, and hammering it buys nothing. Interruptible, so a
            // book closed mid-backoff stops here instead of sleeping through it.
            retryPolicy.sleepBeforeRetry(FIRST_RETRY)
            try {
                return op(connectedShare())
            } catch (second: IOException) {
                throw mappedReconnectFailure(second, first)
            }
        }
    }

    /**
     * Types the second failure with the first suppressed onto it: a definitive second
     * failure propagates itself (the file simply vanished), otherwise exhaustion.
     * Separate function because the retry loop already spends the throw budget.
     */
    private fun mappedReconnectFailure(second: IOException, first: IOException): IOException {
        if (!isTransient(second)) {
            second.addSuppressed(first)
            throw second
        }
        throw TransientExhaustedException(
            "smb transport failed after $RECONNECT_ATTEMPTS attempts",
            second,
        ).also { exhausted -> exhausted.addSuppressed(first) }
    }

    // SMBJ's failure surface is unchecked by design: Share.receive rethrows a dead socket as
    // SMBRuntimeException and an invalidated session or server-closed file as SMBApiException,
    override fun close() {
        // Swap under the lock, close outside it — same teardown rule as dropForReconnect:
        // another thread's blocked teardown never stalls this call, and this call never
        // stalls another thread's teardown.
        val stale = synchronized(guard) {
            closed = true
            connection.also { connection = null }
        }
        if (stale != null) {
            runCatching { stale.close() }
        }
    }

    /**
     * Network-change hook ([TransportInvalidator]): drops the live session without
     * touching the auth latch, so the next read reconnects with the same credentials
     * instead of waiting out the socket timeout. Never throws; the close runs outside
     * the guard like every other teardown here.
     */
    override fun invalidate() {
        val stale = synchronized(guard) {
            connection.also { connection = null }
        }
        if (stale != null) {
            runCatching { stale.close() }
        }
    }

    companion object {
        internal const val AUTH_LATCH_MESSAGE = "smb authentication failed"

        /** Reconnect-once ceiling, preserved: one initial try plus one reconnect. */
        private const val RECONNECT_ATTEMPTS = 2

        /** 1-based failed attempt scheduled before the single reconnect. */
        private const val FIRST_RETRY = 1
    }

    /**
     * One in-flight logon, shared by every thread that arrives mid-establish. Waiters block
     * off-guard and re-check the published state on wake; the finisher always completes,
     * success or failure, so no waiter parks forever.
     */
    private class EstablishTask {
        private val done = java.util.concurrent.CountDownLatch(1)

        fun complete() {
            done.countDown()
        }

        fun awaitEstablished() {
            try {
                done.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted while connecting", e)
            }
        }
    }
}

/**
 * Production [SmbConnector]: config, TCP connect, NTLM logon, share. Anything it opened is
 * closed before the failure escapes, so the transport's latch never sits on a leak.
 */
class SmbjConnector(private val location: SmbLocation) : SmbConnector {

    // SMBJ's failure surface is unchecked by design (SMBApiException and friends extend
    // RuntimeException), so enumerating it would mean pinning a third-party lib's internals.
    @Suppress("TooGenericExceptionCaught")
    override fun connect(password: CharArray): SmbConnection {
        // allowUnsigned is the explicit per-server opt-out for guest and legacy shares:
        // defaults require signing and encryption on SMB3-only dialects. The exact posture
        // lives in SmbConfigFactory, pinned by SmbConfigTest.
        val config = SmbConfigFactory.build(location.allowUnsigned)
        val created = SMBClient(config)
        try {
            val connection = created.connect(location.host, location.port)
            val session = connection.authenticate(
                AuthenticationContext(location.username, password, null),
            )
            // Backslash path: SMB wire format, converted once at the boundary.
            val share = session.connectShare(location.share) as DiskShare
            return SmbjConnection(share, created)
        } catch (e: IOException) {
            runCatching { created.close() }
            throw e
        } catch (e: RuntimeException) {
            runCatching { created.close() }
            throw IOException("smb connect failed", e)
        }
    }
}

/** [DiskShare] behind the testable handle seam. */
private class SmbjConnection(
    private val share: DiskShare,
    private val client: SMBClient,
) : SmbConnection {

    override fun openFile(remotePath: String): RemoteFileHandle {
        // Per the SMBJ sample: null attributes/options mean "no extras", not "missing".
        val file = share.openFile(
            remotePath.replace('/', '\\'),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        return SmbjFileHandle(file)
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { client.close() }
    }
}

private class SmbjFileHandle(
    private val file: com.hierynomus.smbj.share.File,
) : RemoteFileHandle {

    override val length: Long get() = file.fileInformation.standardInformation.endOfFile

    override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
        file.read(buffer, fileOffset, bufferOffset, length)

    override fun close() {
        runCatching { file.close() }
    }
}
