package com.absolutex.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.backup.ExportResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupExportTextTest {
    private fun text(result: ExportResult): String {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val state = result.toBackupState()
        val args = state.messageArguments { id, count -> resources.getQuantityString(id, count, count) }
        return resources.getString(requireNotNull(state.message), *args.toTypedArray())
    }

    @Test fun `one of each item uses singular copy`() {
        assertEquals("Backup exported: 1 book, 1 bookmark, 1 favourite.",
            text(ExportResult(byteArrayOf(), 0, 0, 1, 1, 1)))
    }

    @Test fun `mixed counts and omissions stay accurate`() {
        assertEquals("Backup exported: 1 book, 2 bookmarks, 0 favourites. " +
            "5 older history entries were left out. 3 other items could not be included.",
            text(ExportResult(byteArrayOf(), 3, 5, 1, 2, 0)))
    }
}
