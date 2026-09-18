package com.absolutex.feature.remote

import com.absolutex.remote.core.RangeTransport
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
        val password = secrets.loadSmbPassword(server.id)
            ?: throw IOException("no SMB password for ${server.id}")
        val location = server.toLocation(path)
        return SmbjTransport(location, smbStore(server.id), server.id).bind(location.path)
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
}

/**
 * Dispatches an opened book to the backend owning its server id. Sync (Komga/Kavita)
 * servers hold no files; resolving one is a stale-link caller bug, not IO.
 */
class RemoteBackendResolver @Inject constructor(
    private val servers: com.absolutex.remote.sync.RemoteServers,
    private val smb: SmbBookBackend,
    private val ftp: FtpBookBackend,
) {
    suspend fun transportFor(serverId: String, path: String): RangeTransport {
        return when (val server = servers.current().firstOrNull { it.id == serverId }) {
            is SmbServer -> smb.transportFor(server, path)
            is FtpServer -> ftp.transportFor(server, path)
            is KomgaServer,
            is KavitaServer,
            -> throw IOException("sync servers hold no files: $serverId")
            null -> throw IOException("unknown remote server: $serverId")
        }
    }
}

/** Record plus Uri path to a transport-ready location. Records store roots; Uris are absolute. */
internal fun SmbServer.toLocation(path: String): SmbLocation = SmbLocation(
    host = host,
    share = share,
    path = absolutePath(path),
    port = port,
    username = username,
    allowUnsigned = allowUnsigned,
)

internal fun FtpServer.toLocation(path: String): FtpLocation = FtpLocation(
    host = host,
    port = port,
    username = username,
    path = absolutePath(path),
    useTls = useTls,
)

/**
 * Uri paths address files absolutely within the share. A missing leading slash can only
 * come from a hand-built Uri, so it is repaired rather than rejected.
 */
internal fun absolutePath(path: String): String =
    if (path.startsWith("/")) path else "/$path"
