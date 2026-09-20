package com.absolutex.feature.remote

import com.absolutex.core.scan.LibraryScanner
import com.absolutex.remote.sync.FtpServer
import com.absolutex.remote.sync.RemoteServers
import com.absolutex.remote.sync.SmbServer
import com.absolutex.remote.sync.KavitaServer
import com.absolutex.remote.sync.KomgaServer
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

/** One row in the folder browser: a subfolder, or a book file with its size in bytes. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/**
 * Folder listing over the file backends (SMB/FTP). One method, one concern: the browse
 * ViewModel drives states, this only lists. Blocking on the network — callers stay on IO.
 */
interface RemoteBrowser {
    /** The server record's root folder, absolute: the listing start and the top of back nav. */
    suspend fun rootPath(serverId: String): String

    /** Display rows for [path]: subfolders plus book files, folders first, sorted by name. */
    @Throws(IOException::class)
    suspend fun listDir(serverId: String, path: String): List<RemoteEntry>
}

/**
 * Dispatches a folder listing to the backend owning its server id, like
 * [RemoteBackendResolver] does for opens. Sync (Komga/Kavita) servers hold no files, and
 * an unknown id is a stale-link caller bug — both throw, like the opener, for the
 * browser's generic error path. One transport per listing pass, closed before returning.
 */
class RemoteListingResolver @Inject constructor(
    private val servers: RemoteServers,
    private val smb: SmbBookBackend,
    private val ftp: FtpBookBackend,
) : RemoteBrowser {

    override suspend fun rootPath(serverId: String): String =
        when (val server = current(serverId)) {
            is SmbServer -> absolutePath(server.path.ifBlank { ROOT_PATH })
            is FtpServer -> absolutePath(server.path.ifBlank { ROOT_PATH })
            is KomgaServer,
            is KavitaServer,
            -> throw IOException("sync servers hold no files: $serverId")
        }

    override suspend fun listDir(serverId: String, path: String): List<RemoteEntry> {
        rejectEscape(path)
        return when (val server = current(serverId)) {
            is SmbServer -> {
                smb.listingTransport(server).use { transport ->
                    transport.listDir(path).map { it.toEntry(path) }
                }.toDisplayRows()
            }
            is FtpServer -> {
                ftp.listingTransport(server).use { transport ->
                    transport.listDir(path).map { it.toEntry(path) }
                }.toDisplayRows()
            }
            is KomgaServer,
            is KavitaServer,
            -> throw IOException("sync servers hold no files: $serverId")
        }
    }

    private suspend fun current(serverId: String) =
        servers.current().firstOrNull { it.id == serverId }
            ?: throw IOException("unknown remote server: $serverId")

    private fun rejectEscape(path: String) {
        if (!path.startsWith("/") || path.split('/').any { it == PARENT_SEGMENT }) {
            throw IOException("invalid remote path: $path")
        }
    }

    private fun com.absolutex.remote.smb.SmbEntry.toEntry(parent: String): RemoteEntry =
        RemoteEntry(name, join(parent, name), isDirectory, sizeBytes)

    private fun com.absolutex.remote.ftp.FtpEntry.toEntry(parent: String): RemoteEntry =
        RemoteEntry(name, join(parent, name), isDirectory, sizeBytes)

    private companion object {
        const val PARENT_SEGMENT = ".."
    }
}

/** Absolute child path: the parent is already absolute, the name is a sieved base name. */
internal fun join(parent: String, name: String): String =
    parent.trimEnd('/') + "/" + name

/**
 * Display rows: folders plus book files only, folders first, each by lowercase name.
 * Anything else the server holds (playlists, covers, text files) is not openable here.
 *
 * Two guards live here, where both backends converge: hostile base names die in
 * [isDisplayName] (FTP has no mapper sieve — only SMB's mapper sieves today — so an
 * FTP `..` would otherwise become a tile that walks out of the share), and rows
 * de-duplicate by path (SMB2 enumeration is no atomic snapshot, so a rename-and-
 * replace mid-listing can genuinely repeat a name, and duplicate Compose keys throw
 * at composition time without any hostile server at all).
 */
internal fun List<RemoteEntry>.toDisplayRows(): List<RemoteEntry> =
    filter { isDisplayName(it.name) }
        .filter { it.isDirectory || isBookFile(it.name) }
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        .distinctBy { it.path }

/**
 * Hostile-name sieve: empty, dots, over-long, path-carrying. Mirrors SMB's
 * `isListableName` rules (and its 255 cap) so both backends enforce the same shape
 * at the convergence point regardless of what each mapper does.
 */
internal fun isDisplayName(name: String): Boolean {
    if (name.isEmpty() || name == "." || name == "..") return false
    if (name.length > MAX_DISPLAY_NAME_LENGTH) return false
    if (name.contains('/')) return false
    return true
}

internal const val MAX_DISPLAY_NAME_LENGTH = 255

internal fun isBookFile(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in LibraryScanner.CONTAINER_EXTENSIONS
}
