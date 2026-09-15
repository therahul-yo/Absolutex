package com.absolutex.core.media

import android.content.ContentUris
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.scan.LibraryChange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Observer shell under Robolectric (:core:data pattern): synthetic change URIs in, debounced
 * [LibraryChange] out. Row/dir reads are faked — Robolectric's MediaStore shadow is too thin
 * for cursor assertions, so the platform query path in [PlatformMediaRowSource] is marked
 * TODO(device) and covered here by shape only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreObserverTest {

    private class FakeSource(
        val rows: Map<Long, String> = emptyMap(),
        val stats: Map<String, FolderStats> = emptyMap(),
    ) : MediaRowSource {
        val rowCalls = AtomicInteger(0)
        val statsCalls = AtomicInteger(0)

        override fun rowFor(id: Long): MediaRow? {
            rowCalls.incrementAndGet()
            return rows[id]?.let { MediaRow(id, it) }
        }

        override fun statsFor(dir: File): FolderStats {
            statsCalls.incrementAndGet()
            return stats[dir.path] ?: FolderStats(0, false)
        }
    }

    private fun rowUri(id: Long) =
        ContentUris.withAppendedId(MediaStoreObserver.FILES_EXTERNAL, id)

    private fun observer(
        scope: kotlinx.coroutines.test.TestScope,
        source: FakeSource,
        debounceMs: Long = 50L,
    ): MediaStoreObserver {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return MediaStoreObserver(context.contentResolver, scope, debounceMs, false, source)
    }

    @Test fun `burst for one row emits once and queries once`() = runTest {
        val source = FakeSource(mapOf(7L to "/storage/emulated/0/Download/Batman 001.cbz"))
        val observer = observer(this, source)
        val collected = mutableListOf<LibraryChange>()
        val collect = launch { observer.changes().toList(collected) }

        repeat(5) { observer.handleChange(false, rowUri(7L)) }
        advanceTimeBy(200)
        observer.close()
        collect.cancel()
        advanceUntilIdle()

        assertEquals(listOf(LibraryChange.Added("/storage/emulated/0/Download/Batman 001.cbz")), collected)
        assertEquals(1, source.rowCalls.get())
    }

    @Test fun `bursts separated by the quiet period emit separately`() = runTest {
        val source = FakeSource(mapOf(7L to "/storage/emulated/0/Download/a.cbz"))
        val observer = observer(this, source)
        val collected = mutableListOf<LibraryChange>()
        val collect = launch { observer.changes().toList(collected) }

        observer.handleChange(false, rowUri(7L))
        advanceTimeBy(200)
        observer.handleChange(false, rowUri(7L))
        advanceTimeBy(200)
        observer.close()
        collect.cancel()
        advanceUntilIdle()

        assertEquals(2, collected.size)
    }

    @Test fun `platform self-change flag suppresses without querying`() = runTest {
        val source = FakeSource(mapOf(7L to "/storage/emulated/0/Download/a.cbz"))
        val observer = observer(this, source)
        val collected = mutableListOf<LibraryChange>()
        val collect = launch { observer.changes().toList(collected) }

        observer.handleChange(true, rowUri(7L))
        advanceTimeBy(200)
        observer.close()
        collect.cancel()
        advanceUntilIdle()

        assertTrue(collected.isEmpty())
        assertEquals(0, source.rowCalls.get())
    }

    @Test fun `explicit self-export registration suppresses`() = runTest {
        val path = "/storage/emulated/0/Download/a.cbz"
        val source = FakeSource(mapOf(7L to path))
        val observer = observer(this, source)
        observer.notifySelfExport(path)
        val collected = mutableListOf<LibraryChange>()
        val collect = launch { observer.changes().toList(collected) }

        observer.handleChange(false, rowUri(7L))
        advanceTimeBy(200)
        observer.close()
        collect.cancel()
        advanceUntilIdle()

        assertTrue(collected.isEmpty())
    }

    @Test fun `export-dir path suppresses without registration`() = runTest {
        val source = FakeSource(mapOf(7L to "/storage/emulated/0/Pictures/Absolutex/page01.png"))
        val observer = observer(this, source)
        val collected = mutableListOf<LibraryChange>()
        val collect = launch { observer.changes().toList(collected) }

        observer.handleChange(false, rowUri(7L))
        advanceTimeBy(200)
        observer.close()
        collect.cancel()
        advanceUntilIdle()

        assertTrue(collected.isEmpty())
    }

    @Test fun `unresolvable rows coalesce to one rescan`() = runTest {
        val source = FakeSource()
        val observer = observer(this, source)
        val collected = mutableListOf<LibraryChange>()
        val collect = launch { observer.changes().toList(collected) }

        observer.handleChange(false, rowUri(7L))
        observer.handleChange(false, rowUri(8L))
        advanceTimeBy(200)
        observer.close()
        collect.cancel()
        advanceUntilIdle()

        assertEquals(listOf(LibraryChange.RescanRequested), collected)
    }

    @Test fun `images in one dir cost one bounded census and promote`() = runTest {
        val source = FakeSource(
            rows = mapOf(
                7L to "/storage/emulated/0/Pictures/Loose/001.jpg",
                8L to "/storage/emulated/0/Pictures/Loose/002.jpg",
            ),
            stats = mapOf("/storage/emulated/0/Pictures/Loose" to FolderStats(2, false)),
        )
        val observer = observer(this, source)
        val collected = mutableListOf<LibraryChange>()
        val collect = launch { observer.changes().toList(collected) }

        observer.handleChange(false, rowUri(7L))
        observer.handleChange(false, rowUri(8L))
        advanceTimeBy(200)
        observer.close()
        collect.cancel()
        advanceUntilIdle()

        assertEquals(
            listOf(LibraryChange.FolderPromoted("/storage/emulated/0/Pictures/Loose", 2)),
            collected,
        )
        assertEquals(1, source.statsCalls.get())
    }

    @Test fun `dir-level uri names no row and rescans`() = runTest {
        val source = FakeSource()
        val observer = observer(this, source)
        val collected = mutableListOf<LibraryChange>()
        val collect = launch { observer.changes().toList(collected) }

        observer.handleChange(false, MediaStoreObserver.FILES_EXTERNAL)
        advanceTimeBy(200)
        observer.close()
        collect.cancel()
        advanceUntilIdle()

        assertEquals(listOf(LibraryChange.RescanRequested), collected)
    }

    @Test fun `close is idempotent and stops delivery`() = runTest {
        val source = FakeSource(mapOf(7L to "/storage/emulated/0/Download/a.cbz"))
        val observer = observer(this, source)
        val collected = mutableListOf<LibraryChange>()
        val collect = launch { observer.changes().toList(collected) }

        observer.close()
        observer.close()
        observer.handleChange(false, rowUri(7L))
        advanceTimeBy(200)
        collect.cancel()
        advanceUntilIdle()

        assertTrue(collected.isEmpty())
    }
}
