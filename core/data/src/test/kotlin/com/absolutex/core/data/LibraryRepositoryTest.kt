package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.scan.DocumentTree
import com.absolutex.core.scan.LibraryScanner
import com.absolutex.core.scan.TreeEntry
import com.absolutex.model.BookIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Scanner and database together: what the library screens will actually sit on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AbsolutexDatabase
    private var clock = 1_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun repo() = LibraryRepository(
        dao = db.libraryDao(),
        scanner = LibraryScanner(parallelism = 2),
        now = { clock },
    )

    private fun file(path: String, bytes: Int = 16): File {
        val f = File(tmp.root, path)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test fun `a scan persists what it finds`() = runTest {
        file("Batman/Batman 001.cbz")
        file("Batman/Batman 002.cbz")
        val result = repo().scanLocation(tmp.root)
        assertEquals(2, result.found)
        val stored = db.libraryDao().observeAll().first()
        assertEquals(2, stored.size)
        assertEquals("Batman", stored.first().series)
    }

    /**
     * A filesystem book's contentKey is unaffected by carrying `displayName` through the scan:
     * `File(path).name` already was the real filename here, so the fix changes nothing for it.
     */
    @Test fun `a filesystem-scanned book's contentKey is keyed on its filename, as before`() = runTest {
        val book = file("Batman/Batman 001.cbz", bytes = 12_345)
        repo().scanLocation(tmp.root)
        val row = db.libraryDao().observeAll().first().single()
        assertEquals(book.path, row.path)
        assertEquals(BookIdentity.of("Batman 001.cbz", 12_345), row.contentKey)
    }

    @Test fun `rescanning after a delete removes the book`() = runTest {
        val gone = file("Comics/Gone 001.cbz")
        file("Comics/Kept 001.cbz")
        repo().scanLocation(tmp.root)

        assertTrue(gone.delete())
        clock += 1_000
        val second = repo().scanLocation(tmp.root)

        assertEquals(1, second.found)
        assertEquals(1, second.removed)
        assertEquals(listOf("Kept"), db.libraryDao().observeAll().first().map { it.series })
    }

    @Test fun `rescanning one location never empties another`() = runTest {
        // The bug this guards: an unscoped stale sweep deletes every book on the SD card the
        // moment internal storage is rescanned.
        val sd = File(tmp.root, "sd").apply { mkdirs() }
        val internal = File(tmp.root, "internal").apply { mkdirs() }
        file("sd/Batman 001.cbz")
        file("internal/Superman 001.cbz")

        repo().scanLocation(sd)
        clock += 1_000
        repo().scanLocation(internal)
        clock += 1_000
        val again = repo().scanLocation(internal)

        assertEquals(0, again.removed)
        val series = db.libraryDao().observeAll().first().mapNotNull { it.series }.sorted()
        assertEquals(listOf("Batman", "Superman"), series)
    }

    @Test fun `a rescan keeps the date the book first appeared`() = runTest {
        file("Comics/Batman 001.cbz")
        repo().scanLocation(tmp.root)
        val firstSeen = db.libraryDao().observeAll().first().single().addedAt

        clock += 50_000
        repo().scanLocation(tmp.root)
        val row = db.libraryDao().observeAll().first().single()

        assertEquals("addedAt must survive a rescan or 'recently added' shows everything", firstSeen, row.addedAt)
        assertEquals("seenAtScan must advance", clock, row.seenAtScan)
    }

    @Test fun `a rescan picks up a file that changed size`() = runTest {
        file("Comics/Batman 001.cbz", bytes = 10)
        repo().scanLocation(tmp.root)
        file("Comics/Batman 001.cbz", bytes = 5_000)
        clock += 1_000
        repo().scanLocation(tmp.root)
        assertEquals(5_000L, db.libraryDao().observeAll().first().single().sizeBytes)
    }

    @Test fun `search returns the whole library for a blank query`() = runTest {
        file("Comics/Batman 001.cbz")
        file("Comics/Superman 001.cbz")
        val repo = repo()
        repo.scanLocation(tmp.root)
        assertEquals(2, repo.search("   ").size)
        assertEquals(1, repo.search("batman").size)
    }

    @Test fun `an image folder is stored as one book with its page count`() = runTest {
        file("Loose Pages/001.jpg")
        file("Loose Pages/002.jpg")
        file("Loose Pages/003.jpg")
        repo().scanLocation(tmp.root)
        val book = db.libraryDao().observeAll().first().single()
        assertTrue(book.isImageFolder)
        assertEquals(3, book.pageCount)
    }

    /**
     * The bug this guards: a SAF book's `path` is a `content://` document Uri whose last segment
     * is a percent-encoded document id, e.g. `.../document/primary%3AComics%2FBatman 001.cbz`.
     * `contentKey = BookIdentity.of(File(path).name, sizeBytes)` keyed on that segment instead of
     * the real name, so it could never equal the key the reader computes from
     * `OpenableColumns.DISPLAY_NAME` and writes into `ReadingProgress.bookId` — a SAF book's
     * reading position could never join its library row.
     */
    @Test fun `a SAF-scanned book's contentKey matches what the reader computes`() = runTest {
        val documentUri = "content://com.android.externalstorage.documents/tree/primary%3AComics/" +
            "document/primary%3AComics%2FBatman%20001.cbz"
        val root = TreeEntry(uri = "root", name = "Comics", isDirectory = true)
        val tree = DocumentTree { parent ->
            if (parent == "root") {
                listOf(TreeEntry(uri = documentUri, name = "Batman 001.cbz", isDirectory = false, sizeBytes = 12_345))
            } else {
                emptyList()
            }
        }

        repo().scanTree(root, tree)

        val row = db.libraryDao().observeAll().first().single()
        assertEquals(documentUri, row.path)
        // Exactly what Context.identityOf computes from DISPLAY_NAME/SIZE for the same document.
        assertEquals(BookIdentity.of("Batman 001.cbz", 12_345), row.contentKey)
    }

    // ---------------------------------------------------------------- scanTree (SAF)

    private fun bookTree(vararg entries: Pair<String, List<TreeEntry>>): DocumentTree {
        val map = entries.toMap()
        return DocumentTree { parent -> map[parent].orEmpty() }
    }

    /** One book per directory under root, each `children()` call paced by [delayMs] real millis:
     * slow enough that a real cancellation lands mid-walk, the same way `LibraryScannerTest`
     * paces its own cancellation test. */
    private fun slowSafTree(total: Int, delayMs: Long = 1, onListed: (Int) -> Unit = {}): DocumentTree {
        val listed = AtomicInteger()
        return DocumentTree { parent ->
            when {
                parent == "content://root" ->
                    (0 until total).map { i -> TreeEntry("content://root/dir$i", "Dir $i", isDirectory = true) }
                parent.startsWith("content://root/dir") -> {
                    Thread.sleep(delayMs)
                    onListed(listed.incrementAndGet())
                    val i = parent.removePrefix("content://root/dir")
                    val uri = "content://root/dir$i/book.cbz"
                    listOf(TreeEntry(uri, "Batman $i.cbz", isDirectory = false, sizeBytes = 10))
                }
                else -> emptyList()
            }
        }
    }

    @Test fun `scanTree persists what it finds, the same way scanLocation does`() = runTest {
        val root = TreeEntry(uri = "content://root", name = "root", isDirectory = true)
        val result = repo().scanTree(
            root,
            bookTree(
                "content://root" to listOf(TreeEntry("content://root/a", "Batman 001.cbz", isDirectory = false)),
            ),
        )
        assertEquals(1, result.found)
        assertEquals("Batman", db.libraryDao().observeAll().first().single().series)
    }

    @Test fun `a cancelled scanTree keeps whatever it already wrote`() = runBlocking {
        val root = TreeEntry(uri = "content://root", name = "root", isDirectory = true)
        val total = 3_000
        // Cancel once the walk is provably past the first write, not after a fixed wall-clock
        // delay a slow CI runner may not reach. SafScanner.scan is an unbuffered flow collected in
        // scanTree's own coroutine, so listing directory 150 means the 100-book batch was written.
        val pastFirstBatch = CompletableDeferred<Unit>()
        val tree = slowSafTree(total) { listed -> if (listed >= 150) pastFirstBatch.complete(Unit) }
        val job = launch(Dispatchers.IO) { repo().scanTree(root, tree) }
        withTimeout(30_000) { pastFirstBatch.await() }
        job.cancelAndJoin()

        val stored = db.libraryDao().observeAll().first()
        assertTrue("expected some books written before cancellation", stored.isNotEmpty())
        assertTrue("expected fewer than the full tree, since it was cancelled", stored.size < total)
    }

    @Test fun `a cancelled scanTree does not run the stale sweep`() = runBlocking {
        val root = TreeEntry(uri = "content://root", name = "root", isDirectory = true)
        val repository = repo()
        // A book from an earlier, completed scan of the same root.
        repository.scanTree(
            root,
            bookTree("content://root" to listOf(TreeEntry("content://root/old", "Old 001.cbz", isDirectory = false))),
        )
        assertEquals(1, db.libraryDao().observeAll().first().size)

        clock += 1_000
        val job = launch(Dispatchers.IO) { repository.scanTree(root, slowSafTree(3_000)) }
        delay(300)
        job.cancelAndJoin()

        // A completed second scan would delete "old" as stale (it carries the first scan's id);
        // a cancelled one must never reach that sweep at all.
        assertTrue(db.libraryDao().observeAll().first().any { it.path == "content://root/old" })
    }
}
