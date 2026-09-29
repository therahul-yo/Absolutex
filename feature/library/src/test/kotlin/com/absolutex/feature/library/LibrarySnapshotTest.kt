package com.absolutex.feature.library

import com.absolutex.core.data.ScanSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val A = "content://tree/a"
private const val B = "content://tree/b"

private fun snapshot(
    locations: Set<String> = emptySet(),
    scanning: Set<String> = emptySet(),
    unreadable: Set<String> = emptySet(),
) = LibrarySnapshot(emptyList(), locations, ScanSnapshot(scanning, unreadable))

/** What the empty states are decided from, read together: folders, scans, and what they found. */
class LibrarySnapshotTest {

    @Test fun `no saved folder means no locations`() {
        assertFalse(snapshot().hasLocations)
    }

    @Test fun `a saved folder is a location even with nothing scanned yet`() {
        assertTrue(snapshot(locations = setOf(A)).hasLocations)
    }

    @Test fun `any running scan counts, even for a folder about to be saved`() {
        assertTrue(snapshot(scanning = setOf(A)).scanning)
        assertFalse(snapshot(locations = setOf(A)).scanning)
    }

    @Test fun `a failure counts only for a folder that is still saved`() {
        assertTrue(snapshot(locations = setOf(A), unreadable = setOf(A)).scanFailed)
        assertFalse("a removed folder's old failure is not reported", snapshot(unreadable = setOf(A)).scanFailed)
        assertFalse(snapshot(locations = setOf(B), unreadable = setOf(A)).scanFailed)
    }

    @Test fun `folders scanned clean and empty is the nothing-found state`() {
        val s = snapshot(locations = setOf(A))
        val ui = LibraryUiState.Initial.copy(
            loading = false,
            hasLocations = s.hasLocations,
            scanning = s.scanning,
            scanFailed = s.scanFailed,
        )
        assertEquals(LibraryEmptyReason.LIBRARY_EMPTY, ui.emptyReason)
    }
}
