package com.absolutex.remote.ftp

import java.io.Closeable
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
 */
class CommonsNetFtpTransport(
    private val location: FtpLocation,
    private val password: () -> CharArray,
    private val clientFactory: () -> FTPClient = { defaultClient(location.useTls) },
) : FtpTransport, Closeable {

    private val lock = Any()
    private var client: FTPClient? = null

    override fun sizeBytes(path: String): Long = synchronized(lock) {
        val live = connected()
        // MLST first, LIST fallback: some servers implement only one of the two listings.
        val direct = runCatching { live.mlistFile(path) }.getOrNull()
        val listed = direct ?: runCatching { live.listFiles(path).firstOrNull() }.getOrNull()
        val size = listed?.size ?: fail("cannot stat FTP path: $path")
        if (size < MIN_SIZE) fail("negative size for FTP path: $path")
        size
    }

    override fun readAt(path: String, offset: Long, length: Int): ByteArray = synchronized(lock) {
        require(offset >= MIN_OFFSET) { "negative FTP offset: $offset" }
        require(length >= MIN_LENGTH) { "negative FTP length: $length" }
        if (length == EMPTY_LENGTH) return@synchronized ByteArray(EMPTY_LENGTH)
        try {
            transfer(path, offset, length)
        } catch (expected: IOException) {
            // A failed transfer leaves the control connection desynced; drop it so the next
            // call reconnects instead of speaking mid-transfer to a confused server.
            drop()
            throw expected
        }
    }

    /** Logs out and disconnects. Safe to call more than once. */
    override fun close() = synchronized(lock) { drop() }

    private fun transfer(path: String, offset: Long, length: Int): ByteArray {
        val live = connected()
        live.setRestartOffset(offset)
        val stream = live.retrieveFileStream(path) ?: fail("FTP RETR refused: $path at $offset")
        val out = readFully(stream, length, path, offset)
        // We deliberately close the data stream once our range is in hand, without draining the
        // rest of the file. A real server reports that early close as 426/450/451 (sometimes 226
        // if it was fast enough), not as a plain positive completion, so completePendingCommand()
        // legitimately returns false here even though the transfer we asked for succeeded. Only a
        // reply outside that set (421 - control connection closing - included) is a real failure.
        stream.close()
        val completed = live.completePendingCommand()
        val replyCode = live.replyCode
        if (!completed && replyCode !in EARLY_CLOSE_REPLY_CODES) {
            fail("FTP transfer did not complete: $path at $offset (reply $replyCode)")
        }
        return out
    }

    private fun readFully(stream: InputStream, length: Int, path: String, offset: Long): ByteArray {
        val out = ByteArray(length)
        var done = 0
        // Partial socket reads are normal; loop until the range is exact or the stream ends.
        while (done < length) {
            val count = stream.read(out, done, length - done)
            if (count < 0) fail("short FTP read: $path at ${offset + done} ($done of $length)")
            done += count
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
            if (!fresh.login(location.username, String(secret))) fail("FTP login refused: ${location.uri}")
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

    private fun drop() {
        val stale = client
        client = null
        if (stale != null) {
            runCatching { stale.logout() }
            runCatching { stale.disconnect() }
        }
    }

    private fun fail(message: String): Nothing = throw IOException(message)

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
