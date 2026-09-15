package com.absolutex.feature.library

import kotlinx.coroutines.flow.Flow

/**
 * What the data layer can actually do today.
 *
 * The batch action bar renders the actions §5.1 asks for and omits the ones nothing can service.
 * Omitted rather than disabled, because a disabled control never receives a tap: the previous
 * shape showed a greyed-out Delete that could not explain itself however hard it was pressed.
 * See [LibraryFeed] for which of these is false and why.
 */
internal data class LibraryCapabilities(
    val canFavorite: Boolean,
    val canMarkRead: Boolean,
    val canDelete: Boolean,
) {
    /** Whether there is a single batch action to offer. An empty overflow menu is worse than none. */
    val hasAny: Boolean get() = canFavorite || canMarkRead || canDelete

    companion object {
        val None = LibraryCapabilities(canFavorite = false, canMarkRead = false, canDelete = false)
    }
}

/**
 * Why an action §5.1 asks for has nothing behind it yet.
 *
 * An enum rather than a sentence: the data layer has no `Context` and therefore no string
 * resources, and a reason composed here could never be translated — which is exactly how every
 * message in this class ended up hard-coded English (see [LibraryNotice]). The screens own the
 * words.
 */
internal enum class UnsupportedReason { FAVOURITES_STORE_MISSING, DELETE_NOT_IMPLEMENTED }

/**
 * Something that happened to the library, said in types so the screen can say it in words.
 *
 * The ViewModel is not a place a string resource can be read from, so a message modelled as a
 * `String` here is a message that can only ever be English.
 */
internal sealed interface LibraryNotice {

    /**
     * @param count rows the action changed.
     * @param skipped rows it could not touch — marking a container read needs a page count, and a
     *   container's is unknown until the book has been opened once. Reported rather than
     *   swallowed: silently doing four of five things is worse than saying so.
     */
    data class BatchApplied(val count: Int, val skipped: Int) : LibraryNotice

    data class BatchUnsupported(val reason: UnsupportedReason) : LibraryNotice

    /** The data layer threw. Detail-free on purpose: the screen shows one sentence, not a stack. */
    data object BatchFailed : LibraryNotice

    /** The library could not be read at all, so the rows on screen are not to be trusted. */
    data object LoadFailed : LibraryNotice
}

/**
 * Everything the library screens need from storage, and nothing else.
 *
 * A narrow interface owned by this module rather than the repository itself, for two reasons: it
 * keeps the state layer free of Room types so it can be tested without an Android toolchain, and
 * it gives the screens a single honest place to say which of §5.1's actions the data layer can
 * currently service.
 */
internal interface LibraryFeed {

    val capabilities: LibraryCapabilities

    /** The whole library, re-emitted as scans land. Already deduplicated across locations. */
    fun observeBooks(): Flow<List<LibraryBookUi>>

    /** Substring match over series, title and filename. A blank query means the whole library. */
    suspend fun search(query: String): List<LibraryBookUi>

    suspend fun setFavorite(paths: Set<String>, favorite: Boolean): LibraryNotice

    /**
     * Marks every selected book read, or clears its position.
     *
     * @param read true writes the last page, false writes page 0. A book whose page count neither
     *   the scan nor the reader knows cannot be marked read, and is counted as skipped.
     */
    suspend fun setRead(paths: Set<String>, read: Boolean): LibraryNotice

    suspend fun delete(paths: Set<String>): LibraryNotice
}
