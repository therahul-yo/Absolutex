package com.absolutex.core.data

import com.absolutex.core.scan.LibraryScanner
import com.absolutex.core.scan.ScannedBook
import kotlinx.coroutines.flow.Flow
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

    private fun ScannedBook.toEntity(scanId: Long) = LibraryBook(
        path = path,
        // Identity for cross-location deduplication (§5.1): the same file seen twice through
        // two configured roots. Name and size, because hashing contents is unaffordable.
        contentKey = "${File(path).name}:$sizeBytes",
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
