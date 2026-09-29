package com.absolutex.feature.library

import com.absolutex.core.data.ScanSnapshot

/**
 * The three things the empty states are decided from, read together: the books, the folders the
 * user has saved, and what the scans of those folders are doing.
 *
 * "Has a folder" comes from the saved locations. It used to be inferred from the book list, which
 * made a folder that was added but held nothing indistinguishable from no folder at all.
 */
internal data class LibrarySnapshot(
    val books: List<LibraryBookUi>,
    val locations: Set<String>,
    val scan: ScanSnapshot,
) {
    val hasLocations: Boolean get() = locations.isNotEmpty()

    /** Any scan at all: a scan of a folder about to be saved is still a scan in progress. */
    val scanning: Boolean get() = scan.scanning.isNotEmpty()

    /** Only folders still saved: a removed folder's old failure is not the user's problem. */
    val scanFailed: Boolean get() = scan.unreadable.any { it in locations }
}
