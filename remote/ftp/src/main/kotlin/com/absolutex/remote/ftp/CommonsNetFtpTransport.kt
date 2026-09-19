package com.absolutex.remote.ftp

import com.absolutex.remote.core.CredentialExpiredException
import com.absolutex.remote.core.RetryPolicy
import com.absolutex.remote.core.TransportAuthException
import com.absolutex.remote.core.TransportInvalidator
import com.absolutex.remote.core.TransportPermanentException
import com.absolutex.remote.core.TransientExhaustedException
import com.absolutex.remote.core.TransientTransportException
import com.absolutex.remote.core.isTransient
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient

/**
 * [FtpTransport] over Apache Commons Net (API names verified against the 3.13.0 jar).
 *
 * One control connection, guarded by a lock: transfers serialise, but every [readAt] sets its
 * own REST offset and pulls a fresh data connection via RETR, so concurrent readers never share
 * offset state — the same argument as `LibArchiveSource`'s fresh-descriptor-per-read rule.
 * Blocking; call off the main thread.
 *
 * ponytail: one connection behind one lock is a hard ceiling — every page on this transport
 * queues behind whichever RETR is in flight, so prefetch cannot overlap a foreground read.
 * Upgrade path is a second control connection dedicated to prefetch, not a pool: FTP servers cap
 * connections per user, and two is already the useful number.
 *
 * @param password supplies a fresh password copy per login; the transport zeroes it after use,
 * so providers must hand out a copy (as [InMemoryFtpCredentialStore.load] does), not a live
 * reference. The transient login String cannot be zeroed — its lifetime is one login call.
 *
 * Phase F resilience: bounded retry with backoff around reads, stats and listings, transient
 * failures only ([isTransient]) — each retry drops the desynced control connection and
 * reconnects, re-issuing REST+RETR for the same range (reconnect-and-resume at range
 * granularity). A refused login never retries: the first refusal is [TransportAuthException],
 * and a refusal after a successful login on this transport is [CredentialExpiredException]
 * (the password changed on the NAS mid-session — sign in again). Definitive replies (5xx)
 * map to permanent errors, never the loop. Everything runs under the one lock, backoff
 * included: the reconnect state machine lives under it, and the added latency is bounded
 * (~600ms worst case) rather than the 30s socket timeout a stale connection would burn.
 */
class CommonsNetFtpTransport(
    private val location: FtpLocation,
    private val password: () -> CharArray,
    private val clientFactory: () -> FTPClient = { defaultClient(location.useTls) },
) : FtpTransport, Closeable, TransportInvalidator {

    private val lock = Any()
    private var client: FTPClient? = null

    /**
     * True once any login on this transport succeeded. A later refusal cannot be a typo
     * the user just made — it is rotation — so it surfaces as expiry, not auth failure.
     * Read and written only under [lock].
     */
    private var everAuthenticated = false
    private val retryPolicy: RetryPolicy = RetryPolicy()

    override fun sizeBytes(path: String): Long = synchronized(lock) {
        retrying("stat") { live ->
            // MLST first, LIST fallback: some servers implement only one of the two listings.
            val direct = runCatching { live.mlistFile(path) }.getOrNull()
            val listed = direct ?: runCatching { live.listFiles(path).firstOrNull() }.getOrNull()
            val size = listed?.size ?: throw ftpStatFailure(path, live.replyCode)
            if (size < MIN_SIZE) ftpFail("negative size for FTP path: $path")
            size
        }
    }

    override fun readAt(path: String, offset: Long, length: Int): ByteArray = synchronized(lock) {
        require(offset >= MIN_OFFSET) { "negative FTP offset: $offset" }
        require(length >= MIN_LENGTH) { "negative FTP length: $length" }
        if (length == EMPTY_LENGTH) return@synchronized ByteArray(EMPTY_LENGTH)
        return@synchronized retrying("read") { live -> transfer(live, path, offset, length) }
    }

    /** Logs out and disconnects. Safe to call more than once. */
    override fun close() = synchronized(lock) { drop() }

    override fun listDir(path: String): List<FtpEntry> = synchronized(lock) {
        retrying("list") { live ->
            // LIST, not MLSD: probed against the in-process server, MLSD answers a missing
            // path with 226 and an empty listing — indistinguishable from an empty folder —
            // while LIST answers 450, which is detectable below. Encoding edge cases in LIST
            // output lose to that distinction; revisit if a real server mis-parses.
            val files = live.listFiles(path)
            if (files.isEmpty() && !FTPReply.isPositiveCompletion(live.replyCode)) {
                throw ftpListFailure(path, live.replyCode)
            }
            files.map { file ->
                FtpEntry(file.name.substringAfterLast('/'), file.isDirectory, file.size)
            }
        }
    }

    /**
     * One bounded retry loop for every op above. Failures drop the control connection
     * (a failed transfer leaves it desynced; the next attempt reconnects instead of
     * speaking mid-transfer to a confused server); only transient failures earn
     * another attempt, and exhaustion surfaces as [TransientExhaustedException] with
     * the last failure as cause and earlier attempts suppressed in order.
     */
    private fun <T> retrying(opName: String, op: (FTPClient) -> T): T {
        val prior = ArrayList<IOException>(retryPolicy.maxAttempts)
        var attempt = 0
        while (true) {
            attempt++
            try {
                return op(connected())
            } catch (e: IOException) {
                drop()
                if (!isTransient(e)) {
                    prior.forEach(e::addSuppressed)
                    throw e
                }
                if (attempt >= retryPolicy.maxAttempts) {
                    throw TransientExhaustedException("ftp $opName failed after $attempt attempts", e)
                        .also { exhausted -> prior.forEach(exhausted::addSuppressed) }
                }
                prior += e
                retryPolicy.sleepBeforeRetry(attempt)
            }
        }
    }

    private fun transfer(live: FTPClient, path: String, offset: Long, length: Int): ByteArray {
        live.setRestartOffset(offset)
        val stream = live.retrieveFileStream(path) ?: throw ftpRetrFailure(path, offset, live.replyCode)
        val out = ftpReadFully(stream, length, path, offset)
        // We deliberately close the data stream once our range is in hand, without draining the
        // rest of the file. A real server reports that early close as 426/450/451 (sometimes 226
        // if it was fast enough), not as a plain positive completion, so completePendingCommand()
        // legitimately returns false here even though the transfer we asked for succeeded. Only a
        // reply outside that set (421 - control connection closing - included) is a real failure.
        stream.close()
        val completed = live.completePendingCommand()
        val replyCode = live.replyCode
        if (!completed && replyCode !in EARLY_CLOSE_REPLY_CODES) {
            throw ftpTransferFailure(path, offset, replyCode)
        }
        return out
    }

    private fun connected(): FTPClient {
        val live = client
        if (live != null && live.isConnected) return live
        val fresh = clientFactory()
        fresh.connectTimeout = CONNECT_TIMEOUT_MS
        // setSoTimeout() writes straight to the live socket, which doesn't exist yet; the
        // pre-connect idiom is setDefaultTimeout(), which SocketClient applies to the socket
        // itself right after connect() opens it. The old `fresh.soTimeout = SO_TIMEOUT_MS` here
        // threw a NullPointerException on every real connection — only the unit tests' fake
        // client (which no-ops setSoTimeout) hid it; CommonsNetFtpTransportRealServerTest, which
        // connects for real, caught it.
        fresh.setDefaultTimeout(SO_TIMEOUT_MS)
        fresh.connect(location.host, location.port)
        val secret = password()
        try {
            if (!fresh.login(location.username, String(secret))) {
                // A refused login must not cache the rejected connection — and a refusal
                // after a success is rotation, not a typo. Disconnect best-effort: the
                // server just refused us, so logout would only add a round trip.
                runCatching { fresh.disconnect() }
                throw loginFailure()
            }
            everAuthenticated = true
        } finally {
            secret.fill(CLEARED_CHAR)
        }
        // Passive mode: the server opens the data port, so transfers survive client-side NAT.
        fresh.enterLocalPassiveMode()
        // Binary type: ASCII translation would corrupt archives on the wire.
        fresh.setFileType(FTP.BINARY_FILE_TYPE)
        if (fresh is FTPSClient) {
            fresh.execPBSZ(PROTECTION_BUFFER_ZERO)
            fresh.execPROT(DATA_CHANNEL_PRIVATE)
        }
        client = fresh
        return fresh
    }

    private fun loginFailure(): IOException {
        // The probe matches on the "FTP login refused" prefix (AuthFailed), so both
        // variants keep it: the type, not the message, tells rotation apart.
        return if (everAuthenticated) {
            CredentialExpiredException("FTP login refused: credentials rejected for ${location.uri}")
        } else {
            TransportAuthException("FTP login refused: ${location.uri}")
        }
    }

    private fun drop() {
        val stale = client
        client = null
        if (stale != null) {
            runCatching { stale.logout() }
            runCatching { stale.disconnect() }
        }
    }

    /**
     * Network-change hook ([TransportInvalidator]): drops the control connection
     * without the logout round trip (the path may already be gone) so the next call
     * reconnects instead of waiting out the socket timeout. Never throws.
     */
    override fun invalidate() {
        val stale = synchronized(lock) {
            client.also { client = null }
        }
        if (stale != null) {
            runCatching { stale.disconnect() }
        }
    }


    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val SO_TIMEOUT_MS = 30_000
        private const val MIN_SIZE = 0L
        private const val MIN_OFFSET = 0L
        private const val MIN_LENGTH = 0
        private const val EMPTY_LENGTH = 0
        private const val PROTECTION_BUFFER_ZERO = 0L
        private const val DATA_CHANNEL_PRIVATE = "P"
        private const val CLEARED_CHAR = '\u0000'

        // Replies a server sends for a range read that stops before EOF: the data stream
        // closed with bytes still unsent. CLOSING_DATA_CONNECTION (226) is already a positive
        // completion that completePendingCommand() accepts on its own; it is listed here for
        // documentation.
        private val EARLY_CLOSE_REPLY_CODES = setOf(
            FTPReply.CLOSING_DATA_CONNECTION,
            FTPReply.TRANSFER_ABORTED,
            FTPReply.FILE_ACTION_NOT_TAKEN,
            FTPReply.ACTION_ABORTED,
        )

        private fun defaultClient(useTls: Boolean): FTPClient =
            // Explicit TLS upgrades a plain connection via AUTH; implicit FTPS is not attempted.
            if (useTls) FTPSClient(false) else FTPClient()
    }
}

/** Reply-class arithmetic: 4xx is "try again", 5xx is the final answer. */
private const val REPLY_CLASS_DIVISOR = 100
private const val PERMANENT_REPLY_CLASS = 5

/**
 * Failure mappers, file-private rather than class members: the transport is at its
 * function budget, and these are pure over their arguments (reply codes and paths),
 * so they live here where unit tests can also pin the reply→type table directly.
 */
internal fun ftpReadFully(stream: InputStream, length: Int, path: String, offset: Long): ByteArray {
    val out = ByteArray(length)
    var done = 0
    // Partial socket reads are normal; loop until the range is exact or the stream ends.
    // An ended stream is EOF mid-transfer: transient, safe to resume from REST.
    while (done < length) {
        val count = stream.read(out, done, length - done)
        if (count < 0) {
            throw TransientTransportException(
                "short FTP read: $path at ${offset + done} ($done of $length)",
            )
        }
        done += count
    }
    return out
}

/**
 * A refused RETR classified by reply: 5xx is the server's final answer (550 is the
 * missing file), anything else is a data-connection blip worth one retry.
 */
internal fun ftpRetrFailure(path: String, offset: Long, replyCode: Int): IOException {
    if (replyCode == FTPReply.FILE_UNAVAILABLE) {
        return FileNotFoundException("FTP path not found: $path at $offset (reply $replyCode)")
    }
    val message = "FTP RETR refused: $path at $offset (reply $replyCode)"
    return if (replyCode / REPLY_CLASS_DIVISOR == PERMANENT_REPLY_CLASS) {
        TransportPermanentException(message)
    } else {
        TransientTransportException(message)
    }
}

internal fun ftpTransferFailure(path: String, offset: Long, replyCode: Int): IOException {
    val message = "FTP transfer did not complete: $path at $offset (reply $replyCode)"
    return if (replyCode / REPLY_CLASS_DIVISOR == PERMANENT_REPLY_CLASS) {
        TransportPermanentException(message)
    } else {
        TransientTransportException(message)
    }
}

/**
 * Stat and list failures keep their historical messages (the connection probes
 * match on them) but arrive typed: 5xx is definitive, anything else transient.
 */
internal fun ftpStatFailure(path: String, replyCode: Int): IOException {
    val message = "cannot stat FTP path: $path (reply $replyCode)"
    return if (replyCode / REPLY_CLASS_DIVISOR == PERMANENT_REPLY_CLASS) {
        TransportPermanentException(message)
    } else {
        TransientTransportException(message)
    }
}

internal fun ftpListFailure(path: String, replyCode: Int): IOException {
    val message = "cannot list FTP path: $path (reply $replyCode)"
    return if (replyCode / REPLY_CLASS_DIVISOR == PERMANENT_REPLY_CLASS) {
        FileNotFoundException(message)
    } else {
        TransientTransportException(message)
    }
}

internal fun ftpFail(message: String): Nothing = throw IOException(message)
