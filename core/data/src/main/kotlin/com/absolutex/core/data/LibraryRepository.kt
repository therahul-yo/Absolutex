package com.absolutex.core.data

import com.absolutex.core.scan.LibraryChange
import com.absolutex.core.scan.DocumentTree
import com.absolutex.core.scan.LibraryScanner
import com.absolutex.core.scan.SafScanner
import com.absolutex.core.scan.TreeEntry
import com.absolutex.core.scan.ScannedBook
import com.absolutex.model.BookIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a scan into the persisted library (§5.1), so the result survives process death (§3).
 *
 * Rows are written in batches as the scan produces them rather than collected and written once:
 * a 5,000-book first scan should fill the screen progressively, and a scan cancelled halfway
 * should leave behind the books it did find instead of nothing.
 */
@Singleton
class LibraryRepository internal constructor(
    private val dao: LibraryDao,
    private val scanner: LibraryScanner,
    private val now: () -> Long,
) {

    /**
     * The constructor Hilt uses. Only [dao] is a real dependency.
     *
     * The scanner and clock used to be default arguments on the @Inject constructor itself.
     * Dagger cannot see Kotlin defaults, so it demanded bindings for LibraryScanner and
     * Function0<Long> and the repository was un-injectable. That compiled only because nothing had
     * injected it yet; the library screen was the first caller and hit it. Tests use the internal
     * constructor to supply a fixed clock.
     */
    @Inject constructor(dao: LibraryDao) : this(dao, LibraryScanner(), System::currentTimeMillis)


    fun observeLibrary(): Flow<List<LibraryBook>> = dao.observeAll()

    /** A blank query is not a search for nothing — it is the whole library (§5.1). */
    suspend fun search(query: String): List<LibraryBook> =
        if (query.isBlank()) dao.allOnce() else dao.search(query.trim())

    /**
     * Scans [root] and reconciles the database with what is on disk.
     *
     * Books are tagged with this scan's id as they land; anything under [root] still carrying an
     * older id is gone from disk and is deleted afterwards. The delete is scoped to [root] for a
     * reason: an unscoped sweep would empty every other location the moment one is rescanned.
     *
     * @return how many books are now recorded under [root].
     */
    suspend fun scanLocation(root: File): ScanResult {
        val scanId = now()
        var found = 0
        val batch = ArrayList<LibraryBook>(BATCH)

        scanner.scan(listOf(root)).collect { book ->
            batch += book.toEntity(scanId)
            found++
            if (batch.size >= BATCH) {
                dao.upsertPreservingAddedAt(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) dao.upsertPreservingAddedAt(batch)

        // Only after the walk completes: deleting on a cancelled scan would remove books whose
        // files are still there, simply because the walk never reached them.
        val removed = dao.deleteStaleIn(root.path, scanId)
        return ScanResult(found = found, removed = removed)
    }

    /**
     * Scans a SAF tree the user granted (§5.1), the same way [scanLocation] scans a directory.
     *
     * Rows carry the document Uri as their path, which is what the reader opens and what the
     * stale sweep scopes by: every document Uri under a tree starts with that tree's own Uri.
     *
     * Batched and cancellation-cooperative exactly like [scanLocation]: [SafScanner.scan] is a
     * Flow now rather than a fully materialised list, so a book lands in the database as it is
     * found, and a scan killed mid-walk (backgrounded and reclaimed, or the user leaving the
     * screen) keeps whatever it already wrote instead of losing the walk to that point. Should
     * collecting throw — cancellation included — the lines below never run, so a cancelled walk
     * cannot delete books it simply never reached.
     */
    suspend fun scanTree(root: TreeEntry, tree: DocumentTree, includeHidden: Boolean = false): ScanResult {
        val scanId = now()
        var found = 0
        val batch = ArrayList<LibraryBook>(BATCH)

        withContext(Dispatchers.IO) {
            SafScanner.scan(root, tree, includeHidden).collect { book ->
                batch += book.toEntity(scanId)
                found++
                if (batch.size >= BATCH) {
                    dao.upsertPreservingAddedAt(batch)
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) dao.upsertPreservingAddedAt(batch)

        // Only after the walk completes, as in scanLocation: a cancelled walk must not delete the
        // books it simply never reached.
        val removed = dao.deleteStaleIn(root.uri, scanId)
        return ScanResult(found = found, removed = removed)
    }

    /**
     * Applies one change from the library's event stream to the persisted library (§5.1).
     *
     * The stream's contract is delta-or-rewalk: `Added` and `Modified` arrive only for paths the
     * watcher says exist, `Removed` only for paths it says are gone, and `RescanRequested`
     * whenever that is not trustworthy. Each arm checks its own precondition against disk anyway,
     * because the watcher reports what it saw and disk is what is true.
     *
     * @param locationRoot the root the change's location maps to, or null when the change is for
     *   a location this repository does not know. An unknown location is dropped: without a root
     *   to scope under, a rescan's stale sweep could reach another location's rows.
     */
    suspend fun applyChange(change: LibraryChange, locationRoot: File?): ChangeResult {
        if (locationRoot == null) return ChangeResult.Ignored
        return when (change) {
            is LibraryChange.Added -> upsertOne(change.path)
            is LibraryChange.Modified -> upsertOne(change.path)
            is LibraryChange.FolderPromoted -> upsertOne(change.path)
            is LibraryChange.Removed -> {
                dao.deletePath(change.path)
                ChangeResult.Removed(path = change.path)
            }
            LibraryChange.RescanRequested -> {
                scanLocation(locationRoot)
                ChangeResult.Rescanned(root = locationRoot)
            }
        }
    }

    /**
     * Writes the one book at [path], or reports why there is nothing to write.
     *
     * The parse goes through [LibraryScanner.scanFile] — the same predicates a full walk uses —
     * so an `Added` for something the scanner would not pick up (junk, hidden, a half-written
     * file that vanished before the write) is a `NotABook`, not a crash and not a row.
     */
    private suspend fun upsertOne(path: String): ChangeResult {
        val book = LibraryScanner.scanFile(File(path)) ?: return ChangeResult.NotABook(path)
        dao.upsertPreservingAddedAt(listOf(book.toEntity(now())))
        return ChangeResult.Upserted(path = path)
    }

    private fun ScannedBook.toEntity(scanId: Long) = LibraryBook(
        path = path,
        // Identity for cross-location deduplication (§5.1) and for joining reading progress,
        // which the reader keys the same way (see Context.identityOf): name and size, because
        // hashing contents is unaffordable. displayName, not File(path).name — path is a
        // content:// document Uri for a SAF-scanned book, and its last segment is a
        // percent-encoded document id, not the filename. No migration needed: contentKey already
        // exists, and a location rescans whenever the library opens, so every row gets the
        // corrected key on its next scan.
        contentKey = BookIdentity.of(displayName, sizeBytes),
        series = parsed.series,
        title = parsed.title,
        issue = parsed.issue?.value,
        issueRaw = parsed.issue?.raw,
        volume = parsed.volume,
        year = parsed.year,
        sizeBytes = sizeBytes,
        lastModified = File(path).lastModified(),
        isImageFolder = isImageFolder,
        pageCount = imageCount.takeIf { it > 0 },
        addedAt = scanId,
        seenAtScan = scanId,
    )

    private companion object {
        /** Big enough to amortise the transaction, small enough that the UI fills as it goes. */
        const val BATCH = 100
    }
}

data class ScanResult(val found: Int, val removed: Int)

/**
 * What applying one [LibraryChange] did.
 *
 * Sealed rather than `Unit` so the location layer can schedule a re-walk only when it must: the
 * watcher already answered renames with a rescan, and the location layer picks which root a path
 * belongs to, so the repository's job is only to say what it did with what it was given.
 */
sealed interface ChangeResult {
    data class Upserted(val path: String) : ChangeResult
    data class Removed(val path: String) : ChangeResult
    data class Rescanned(val root: File) : ChangeResult

    /** The path named no book the scanner would keep: junk, hidden, or gone before the write. */
    data class NotABook(val path: String) : ChangeResult

    /** A change for a location nobody registered. Dropped, because there is no root to scope under. */
    data object Ignored : ChangeResult
}
