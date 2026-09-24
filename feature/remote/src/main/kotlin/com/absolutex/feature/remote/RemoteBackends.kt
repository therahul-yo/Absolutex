package com.absolutex.feature.remote

import com.absolutex.remote.core.RangeTransport
import com.absolutex.remote.core.TransportInvalidator
import com.absolutex.remote.ftp.CommonsNetFtpTransport
import com.absolutex.remote.ftp.FtpLocation
import com.absolutex.remote.ftp.bind
import com.absolutex.remote.smb.SmbCredentialStore
import com.absolutex.remote.smb.SmbLocation
import com.absolutex.remote.smb.SmbjTransport
import com.absolutex.remote.smb.bind
import com.absolutex.remote.sync.FtpServer
import com.absolutex.remote.sync.SmbServer
import com.absolutex.remote.sync.KavitaServer
import com.absolutex.remote.sync.KomgaServer
import com.absolutex.remote.sync.RemoteServers
import com.absolutex.remote.sync.SyncSecrets
import java.io.IOException
import javax.inject.Inject

/**
 * SMB backend: resolves a server record plus its stored secret into a bound file
 * transport. Transports connect lazily on first read and are owned by the caller —
 * [RemoteBackendResolver] hands them to the opener, which closes them with the book.
 */
class SmbBookBackend @Inject constructor(
    private val secrets: SyncSecrets,
) {
    fun transportFor(server: SmbServer, path: String): RangeTransport {
        // Presence check only: the transport retrieves (and zeroes) its own copy later
        // through smbStore, so this copy must not outlive the check. A live plaintext
        // password with no bounded lifetime is exactly what a heap dump carries away.
        val password = secrets.loadSmbPassword(server.id)
            ?: throw IOException("no SMB password for ${server.id}")
        password.fill(Char.MIN_VALUE)
        val location = server.toLocation(path)
        return SmbjTransport(location, smbStore(server.id), server.id).bind(location.path)
    }

    /**
     * Unbound share transport for folder listing: same credentials, no file attached. The
     * caller closes it after listing — one transport per listing pass, never shared.
     */
    fun listingTransport(server: SmbServer): SmbjTransport {
        // Presence check only, same discipline as transportFor above: the transport pulls
        // (and zeroes) its own copy later, so this copy dies with the check.
        val password = secrets.loadSmbPassword(server.id)
            ?: throw IOException("no SMB password for ${server.id}")
        password.fill(Char.MIN_VALUE)
        val location = server.toLocation(server.path.ifBlank { ROOT_PATH })
        return SmbjTransport(location, smbStore(server.id), server.id)
    }

    private fun smbStore(serverId: String): SmbCredentialStore =
        object : SmbCredentialStore {
            override fun store(alias: String, password: CharArray) {
                secrets.saveSmbPassword(serverId, password)
            }

            override fun retrieve(alias: String): CharArray? =
                secrets.loadSmbPassword(serverId)

            override fun clear(alias: String) {
                secrets.clearSmbPassword(serverId)
            }
        }
}

/**
 * FTP backend: same shape over FTPS-capable Commons Net. The password supplier hands out
 * a fresh copy per logon; the transport zeroes each copy after use.
 */
class FtpBookBackend @Inject constructor(
    private val secrets: SyncSecrets,
) {
    fun transportFor(server: FtpServer, path: String): RangeTransport {
        val location = server.toLocation(path)
        return CommonsNetFtpTransport(location, {
            secrets.loadFtpPassword(server.id)?.copyOf()
                ?: throw IOException("no FTP password for ${server.id}")
        }).bind(location.path)
    }

    /**
     * Unbound control connection for folder listing: same password discipline, no file
     * attached. The caller closes it after listing.
     */
    fun listingTransport(server: FtpServer): CommonsNetFtpTransport {
        val location = server.toLocation(server.path.ifBlank { ROOT_PATH })
        return CommonsNetFtpTransport(location, {
            secrets.loadFtpPassword(server.id)?.copyOf()
                ?: throw IOException("no FTP password for ${server.id}")
        })
    }
}

/**
 * Dispatches an opened book to the backend owning its server id. Sync (Komga/Kavita)
 * servers hold no files; resolving one is a stale-link caller bug, not IO.
 *
 * Every returned transport is watched by [RemoteNetworkMonitor] until the book closes:
 * the [WatchedTransport] wrapper below binds the watch handle's lifetime to the book
 * session the opener owns, so the monitor never retains a closed book.
 */
class RemoteBackendResolver @Inject constructor(
    private val servers: com.absolutex.remote.sync.RemoteServers,
    private val smb: SmbBookBackend,
    private val ftp: FtpBookBackend,
    private val networkMonitor: RemoteNetworkMonitor,
) {
    suspend fun transportFor(serverId: String, path: String): RangeTransport {
        val raw = when (val server = servers.current().firstOrNull { it.id == serverId }) {
            is SmbServer -> smb.transportFor(server, path)
            is FtpServer -> ftp.transportFor(server, path)
            is KomgaServer,
            is KavitaServer,
            -> throw IOException("sync servers hold no files: $serverId")
            null -> throw IOException("unknown remote server: $serverId")
        }
        val invalidator = raw as? TransportInvalidator ?: return raw
        return WatchedTransport(raw, networkMonitor.watch(invalidator))
    }
}

/**
 * A book's transport plus its network-change watch handle: closing the book unwatches
 * first, then closes the session. The watch close never fails the book close — the
 * transport close is what releases the session.
 */
private class WatchedTransport(
    private val delegate: RangeTransport,
    private val watch: AutoCloseable,
) : RangeTransport {
    override fun sizeBytes(): Long = delegate.sizeBytes()

    override fun readAt(offset: Long, length: Int): ByteArray = delegate.readAt(offset, length)

    override fun close() {
        runCatching { watch.close() }
        delegate.close()
    }
}

/** Record plus Uri path to a transport-ready location. Records store roots; Uris are absolute. */
internal fun SmbServer.toLocation(path: String): SmbLocation {
    try {
        return SmbLocation(
            host = host,
            share = share,
            path = absolutePath(path),
            port = port,
            username = username,
            allowUnsigned = allowUnsigned,
        )
    } catch (e: IllegalArgumentException) {
        // A stale Uri or a hostile listing can carry .. segments the record validators
        // would never have stored: degrade to IOException (which the reader catches)
        // rather than crashing on a require.
        throw IOException("invalid SMB path: $path", e)
    }
}

internal fun FtpServer.toLocation(path: String): FtpLocation {
    try {
        return FtpLocation(
            host = host,
            port = port,
            username = username,
            path = absolutePath(path),
            useTls = useTls,
        )
    } catch (e: IllegalArgumentException) {
        throw IOException("invalid FTP path: $path", e)
    }
}

/**
 * Uri paths address files absolutely within the share. A missing leading slash can only
 * come from a hand-built Uri, so it is repaired rather than rejected.
 */
internal fun absolutePath(path: String): String =
    if (path.startsWith("/")) path else "/$path"

/** Share root: the listing start when a record stores no sub-path. */
internal const val ROOT_PATH = "/"
