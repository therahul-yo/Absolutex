package com.absolutex.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
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

    private fun connectedShare(): SmbConnection {
        synchronized(guard) {
            if (closed) throw IOException("transport closed")
            authFailure?.let { throw it }
            connection?.let { return it }
            return establish()
        }
    }

    /** Logon under guard. Failure latches: see the class KDoc. */
    private fun establish(): SmbConnection {
        val password = storedPassword()
        try {
            val established = connector.connect(password)
            connection = established
            return established
        } catch (e: IOException) {
            authFailure = e
            throw e
        } finally {
            password.fill(Char.MIN_VALUE)
        }
    }

    private fun storedPassword(): CharArray =
        credentials.retrieve(credentialAlias)
            ?: throw IOException("no stored credentials for $credentialAlias")

    /** Drops the share without latching: the next read reconnects once. */
    private fun dropForReconnect() {
        synchronized(guard) {
            runCatching { connection?.close() }
            connection = null
        }
    }

    override fun sizeBytes(remotePath: String): Long {
        return reconnecting {
            connectedShare().openFile(remotePath).use { it.length }
        }
    }

    override fun readAt(remotePath: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        if (length == 0) return ByteArray(0)
        return reconnecting { readOnce(remotePath, offset, length) }
    }

    /**
     * One revalidation: the failure may be a dead share, in which case the retry reconnects;
     * if it was the logon, the retry rethrows the remembered error instead of logging on
     * again. The second failure always propagates, carrying the first as suppressed context —
     * no loops, no storms, no lost evidence.
     */
    private inline fun <T> reconnecting(op: () -> T): T {
        try {
            return op()
        } catch (first: IOException) {
            dropForReconnect()
            try {
                return op()
            } catch (second: IOException) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun readOnce(remotePath: String, offset: Long, length: Int): ByteArray {
        // Fresh handle per call, so concurrent readers never share a file offset.
        connectedShare().openFile(remotePath).use { file ->
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
    }

    override fun close() {
        synchronized(guard) {
            closed = true
            runCatching { connection?.close() }
            connection = null
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
        // defaults require signing and encryption on SMB3-only dialects.
        val config = if (location.allowUnsigned) {
            SmbConfig.builder()
                .withDialects(
                    SMB2Dialect.SMB_2_1,
                    SMB2Dialect.SMB_3_0,
                    SMB2Dialect.SMB_3_0_2,
                    SMB2Dialect.SMB_3_1_1,
                )
                .build()
        } else {
            SmbConfig.builder()
                .withDialects(
                    SMB2Dialect.SMB_3_0,
                    SMB2Dialect.SMB_3_0_2,
                    SMB2Dialect.SMB_3_1_1,
                )
                .withSigningRequired(true)
                .withEncryptData(true)
                .build()
        }
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
