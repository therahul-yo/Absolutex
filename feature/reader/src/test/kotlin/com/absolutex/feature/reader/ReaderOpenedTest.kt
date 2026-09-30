package com.absolutex.feature.reader

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.settings.InMemorySettings
import com.absolutex.model.Page
import com.absolutex.remote.core.RemoteBookOpener
import com.absolutex.remote.core.RemoteOpenResult
import com.absolutex.source.ComicSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderOpenedTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AbsolutexDatabase
    private val id = "a.cbz:100"
    private val uri = Uri.parse("content://books/a")

    @Before fun setUp() {
        val direct = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AbsolutexDatabase::class.java,
        ).setQueryExecutor(direct).setTransactionExecutor(direct).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun vm(fail: Boolean = false): ReaderViewModel {
        val prefs = InMemorySettings()
        return ReaderViewModel(
            ApplicationProvider.getApplicationContext(), db.progressDao(), 8L * 1024 * 1024 * 1024,
            prefs, prefs, prefs,
            BookOpener { _, _ ->
                if (fail) throw IOException("unreadable")
                object : ComicSource {
                    override val pages = (0..19).map { Page(it, "page-$it.jpg") }
                    override fun openPage(index: Int) = ByteArrayInputStream(byteArrayOf())
                    override fun close() = Unit
                } to id
            },
            object : RemoteBookOpener {
                override suspend fun open(uri: String): RemoteOpenResult = throw IOException("unexpected remote open")
            },
        )
    }

    @Test fun `successful open records page one without a page change`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.open(uri)
        advanceUntilIdle()
        assertEquals(0, vm.ui.value.currentPage)
        assertEquals(0, db.progressDao().get(id)?.pageIndex)
        assertEquals(20, db.progressDao().get(id)?.pageCount)
        assertEquals(1, db.pageViewDao().count())
    }

    @Test fun `open resumes and preserves a real later position`() = runTest(main.dispatcher) {
        db.progressDao().upsert(ReadingProgress(id, 12, 20, 1))
        val vm = vm()
        vm.open(uri)
        advanceUntilIdle()
        assertEquals(12, vm.ui.value.currentPage)
        assertEquals(12, db.progressDao().get(id)?.pageIndex)
    }

    @Test fun `a failed reading-data write does not prevent showing the book`() = runTest(main.dispatcher) {
        db.openHelper.writableDatabase.execSQL("DROP TABLE reading_progress")
        val vm = vm()
        vm.open(uri)
        advanceUntilIdle()
        assertFalse(vm.ui.value.loading)
        assertEquals(20, vm.ui.value.pageCount)
        assertEquals(0, vm.ui.value.currentPage)
    }

    @Test fun `failed open never creates reading progress`() = runTest(main.dispatcher) {
        val vm = vm(fail = true)
        vm.open(uri)
        advanceUntilIdle()
        assertNull(db.progressDao().get(id))
    }
}
