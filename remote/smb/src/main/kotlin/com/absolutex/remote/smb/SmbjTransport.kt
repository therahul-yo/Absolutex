package com.absolutex.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.EnumSet

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
 */
class SmbjTransport(
    private val location: SmbLocation,
    private val credentials: SmbCredentialStore,
    private val credentialAlias: String,
    private val connector: SmbConnector = SmbjConnector(location),
) : SmbTransport {

    private val guard = Any()
    private var connection: SmbConnection? = null
    private var authFailure: IOException? = null
    private var closed = false
    private var establishTask: EstablishTask? = null

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
            password = storedPassword(credentials, credentialAlias)
            installEstablished(connector.connect(password))
        } catch (e: IOException) {
            synchronized(guard) {
                if (authFailure == null) {
                    authFailure = e
                }
            }
            throw e
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
            readLength(share, remotePath)
        }
    }

    override fun readAt(remotePath: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        if (length == 0) return ByteArray(0)
        return reconnecting { share -> readOnce(share, remotePath, offset, length) }
    }

    override fun listDir(remotePath: String): List<SmbEntry> {
        return reconnecting { share -> listShareEntries(share, remotePath) }
    }

    /**
     * One revalidation: the failure may be a dead share, in which case the retry reconnects;
     * if it was the logon, the retry rethrows the remembered error instead of logging on
     * again. The second failure always propagates, carrying the first as suppressed context —
     * no loops, no storms, no lost evidence. An establish failure is not retried at all:
     * there is no share to drop and the retry could only re-hit the latch.
     */
    private inline fun <T> reconnecting(op: (SmbConnection) -> T): T {
        val share = connectedShare()
        try {
            return op(share)
        } catch (first: IOException) {
            dropForReconnect(share)
            try {
                return op(connectedShare())
            } catch (second: IOException) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readLength(share: SmbConnection, remotePath: String): Long {
        try {
            share.openFile(remotePath).use { return it.length }
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("smb stat failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readOnce(share: SmbConnection, remotePath: String, offset: Long, length: Int): ByteArray {
        try {
            // Fresh handle per call, so concurrent readers never share a file offset.
            share.openFile(remotePath).use { file ->
                val out = ByteArray(length)
                var done = 0
                // File.read may return fewer bytes than asked — one call is not the range.
                while (done < length) {
                    val got = file.read(out, offset + done, done, length - done)
                    if (got <= 0) throw IOException("short read at $offset ($done of $length bytes)")
                    done += got
                }
                return out
            }
        } catch (e: Exception) {
            // Genuine IOExceptions pass through unwrapped, so the suppressed chains stay
            // clean; anything else is the unchecked surface above, wrapped for the retry.
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            throw e as? IOException ?: IOException("smb read failed", e)
        }
    }

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

    companion object {
        internal const val AUTH_LATCH_MESSAGE = "smb authentication failed"
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

    override fun listDir(remotePath: String): List<SmbEntry> {
        // DiskShare.list materialises the whole query before returning, so there is no
        // handle to close here — the mapping below owns the only copy from this point on.
        return mapSmbEntries(share.list(wireDir(remotePath)))
    }

    /**
     * Wire form of a listing path: backslash separators, relative to the share. The share
     * root ("/") queries as the empty path; anything else keeps its segments.
     */
    private fun wireDir(remotePath: String): String =
        remotePath.replace('/', '\\').trimStart(WIRE_SEPARATOR)

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

/**
 * Reads one stored password for the single-flight logon. Top-level (not a member) so the
 * transport stays within its function budget; the latch contract in [SmbjTransport] is
 * unchanged — a tampered credential file still latches every queued read exactly once.
 */
internal fun storedPassword(credentials: SmbCredentialStore, credentialAlias: String): CharArray {
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
 * One listing attempt: the unchecked smbj surface is wrapped where the DiskShare call
 * happens, so the transport's retry only ever sees the IOException the seam promises.
 * Both caught types are smbj-specific (never a generic Exception/RuntimeException
 * catch), so an unexpected failure still surfaces instead of masquerading as a missing
 * folder. A genuine IOException passes through unwrapped, like the read path.
 */
internal fun listShareEntries(share: SmbConnection, remotePath: String): List<SmbEntry> {
    try {
        return share.listDir(remotePath)
    } catch (e: SMBApiException) {
        throw IOException("smb list failed", e)
    } catch (e: SMBRuntimeException) {
        throw IOException("smb list failed", e)
    }
}

/** Wire separator for SMB paths, converted once at the boundary like openFile does. */
private const val WIRE_SEPARATOR = '\\'
/** Base-name ceiling mirroring the filesystem limit: longer is a hostile entry, not a file. */
internal const val MAX_SMB_ENTRY_NAME_LENGTH = 255

/** MS-FSCC FileAttributes FILE_ATTRIBUTE_DIRECTORY bit, read off the wire attributes. */
private const val DIRECTORY_ATTRIBUTE_MASK = 0x10L

/**
 * Real mapping from smbj's listing rows to [SmbEntry]: the directory bit decides the kind,
 * end-of-file the size. Hostile rows are skipped, never fatal to the whole folder — one
 * crafted name must not hide its well-behaved siblings, and a name that could escape the
 * share on rejoin (`..`, or anything carrying a separator) never becomes an entry.
 */
internal fun mapSmbEntries(
    infos: List<FileIdBothDirectoryInformation>,
): List<SmbEntry> = infos.mapNotNull { info ->
    val name = info.fileName
    if (!isListableName(name)) {
        null
    } else {
        SmbEntry(
            name = name,
            isDirectory = info.fileAttributes and DIRECTORY_ATTRIBUTE_MASK != 0L,
            sizeBytes = info.endOfFile,
        )
    }
}

internal fun isListableName(name: String): Boolean {
    if (name.isEmpty() || name == "." || name == "..") return false
    if (name.length > MAX_SMB_ENTRY_NAME_LENGTH) return false
    // Base names carry no separators: one that does smuggles a path, not a name.
    if (name.contains('/') || name.contains(WIRE_SEPARATOR)) return false
    return true
}
