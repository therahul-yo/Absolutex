package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.scan.DocumentTree
import com.absolutex.core.scan.LibraryScanner
import com.absolutex.core.scan.TreeEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/** An unreadable folder is not evidence that its books were deleted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryScanSafetyTest {
    private lateinit var db: AbsolutexDatabase
    private var clock = 1_000L
    private val root = TreeEntry("content://root", "root", isDirectory = true)
    private val folder = TreeEntry("content://root/bad", "bad", isDirectory = true)
    private val favourite = TreeEntry("content://root/bad/favourite", "Favourite.cbz", isDirectory = false)
    private val gone = TreeEntry("content://root/gone", "Gone.cbz", isDirectory = false)
    private val found = TreeEntry("content://root/found", "Found.cbz", isDirectory = false)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun repo() = LibraryRepository(db.libraryDao(), LibraryScanner(parallelism = 2), { clock })

    private fun tree(
        entries: Map<String, List<TreeEntry>>,
        failures: ReadFailures? = null,
        unreadable: Set<String> = emptySet(),
    ) = DocumentTree { parent ->
        if (parent in unreadable) {
            failures?.record()
            emptyList()
        } else {
            entries[parent].orEmpty()
        }
    }

    private suspend fun seed() {
        repo().scanTree(root, tree(mapOf(root.uri to listOf(folder, gone), folder.uri to listOf(favourite))))
        repo().upsertFavorite(favourite.uri, true)
        clock += 1_000
    }

    @Test fun `an unreadable subfolder preserves every old row and keeps found books`() = runTest {
        seed()
        val before = db.libraryDao().allOnce().associateBy { it.path }
        val failures = ReadFailures()
        val result = repo().scanTree(
            root,
            tree(mapOf(root.uri to listOf(folder, found)), failures, setOf(folder.uri)),
            failures = failures,
        )
        assertEquals(ScanResult(found = 1, removed = 0, incomplete = true), result)
        val after = db.libraryDao().allOnce().associateBy { it.path }
        assertEquals(before[favourite.uri], after[favourite.uri])
        assertEquals(before[gone.uri], after[gone.uri])
        assertTrue(after.getValue(favourite.uri).isFavorite)
        assertEquals(clock, after.getValue(found.uri).seenAtScan)
    }

    @Test fun `a readable walk removes stale rows and preserves a seen favourite origin`() = runTest {
        seed()
        val before = db.libraryDao().allOnce().single { it.path == favourite.uri }
        val result = repo().scanTree(
            root, tree(mapOf(root.uri to listOf(folder), folder.uri to listOf(favourite))),
            failures = ReadFailures(),
        )
        assertEquals(ScanResult(found = 1, removed = 1), result)
        assertFalse(result.incomplete)
        val after = db.libraryDao().allOnce().single()
        assertTrue(after.isFavorite)
        assertEquals(before.addedAt, after.addedAt)
        assertEquals(clock, after.seenAtScan)
    }

    @Test fun `an unreadable root deletes nothing`() = runTest {
        seed()
        val before = db.libraryDao().allOnce()
        val failures = ReadFailures()
        val result = repo().scanTree(root, tree(emptyMap(), failures, setOf(root.uri)), failures = failures)
        assertEquals(ScanResult(found = 0, removed = 0, incomplete = true), result)
        assertEquals(before, db.libraryDao().allOnce())
    }

    @Test fun `cancellation after a batch preserves old rows without sweeping`() = runBlocking {
        seed()
        val before = db.libraryDao().allOnce().associateBy { it.path }
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val interrupted = DocumentTree { parent ->
            if (parent == root.uri) {
                (0 until 100).map { TreeEntry("${root.uri}/new$it", "New $it.cbz", isDirectory = false) } + folder
            } else {
                entered.complete(Unit)
                release.await()
                emptyList()
            }
        }
        val job = launch(Dispatchers.IO) { repo().scanTree(root, interrupted, failures = ReadFailures()) }
        try {
            withTimeout(30_000) { entered.await() }
        } finally {
            job.cancel()
            release.countDown()
            job.join()
        }
        val after = db.libraryDao().allOnce().associateBy { it.path }
        assertEquals(102, after.size)
        assertEquals(before[favourite.uri], after[favourite.uri])
        assertEquals(before[gone.uri], after[gone.uri])
    }

    @Test fun `forgetting an unreadable location still deliberately deletes its rows`() = runTest {
        seed()
        assertEquals(2, repo().forgetLocation(root.uri))
        assertTrue(db.libraryDao().allOnce().isEmpty())
    }
}
