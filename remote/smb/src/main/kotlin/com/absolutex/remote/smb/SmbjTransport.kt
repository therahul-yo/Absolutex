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
 * SMBJ-backed [SmbTransport]. SMB2/SMB3 only: the dialect list names 2.1 through 3.1.1 and
 * SMBJ's dialect enum has no SMB1 constant at all, so SMB1 negotiation is impossible by
 * construction, not by flag. Signatures verified against smbj-0.15.0
 * (javap over the Central jar, 2026-09-15) rather than docs.
 *
 * Blocking by design (SMBJ is a blocking API) — callers must stay off the main thread,
 * same as local archive extraction on DecodeDispatchers.extract.
 */
class SmbjTransport(
    private val location: SmbLocation,
    private val credentials: SmbCredentialStore,
    private val credentialAlias: String,
) : SmbTransport {

    private val guard = Any()
    private var client: SMBClient? = null
    private var share: DiskShare? = null

    private fun connectedShare(): DiskShare {
        synchronized(guard) {
            share?.let { return it }
            val password = credentials.retrieve(credentialAlias)
                ?: throw IOException("no stored credentials for $credentialAlias")
            try {
                // Dialects pinned: 2.1+ only. Defaults kept for auth (NTLMv2) and signing.
                val config = SmbConfig.builder()
                    .withDialects(
                        SMB2Dialect.SMB_2_1,
                        SMB2Dialect.SMB_3_0,
                        SMB2Dialect.SMB_3_0_2,
                        SMB2Dialect.SMB_3_1_1,
                    )
                    .build()
                val created = SMBClient(config)
                val connection = created.connect(location.host, location.port)
                val session = connection.authenticate(
                    AuthenticationContext(location.username, password, null),
                )
                // Backslash path: SMB wire format, converted once at the boundary.
                val connected = session.connectShare(location.share) as DiskShare
                client = created
                share = connected
                return connected
            } finally {
                password.fill(Char.MIN_VALUE)
            }
        }
    }

    override fun sizeBytes(remotePath: String): Long {
        open(remotePath).use { return it.length }
    }

    override fun readAt(remotePath: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        if (length == 0) return ByteArray(0)
        // Fresh handle per call, so concurrent readers never share a file offset.
        open(remotePath).use { file ->
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

    private fun open(remotePath: String): com.hierynomus.smbj.share.File {
        // Per the SMBJ sample: null attributes/options mean "no extras", not "missing".
        return connectedShare().openFile(
            remotePath.replace('/', '\\'),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    override fun close() {
        synchronized(guard) {
            runCatching { share?.close() }
            runCatching { client?.close() }
            share = null
            client = null
        }
    }
}
