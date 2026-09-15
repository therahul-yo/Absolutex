package com.absolutex.feature.library

import kotlinx.coroutines.flow.Flow

/**
 * What the data layer can actually do today.
 *
 * The batch action bar renders every action §5.1 asks for and disables the ones nothing can
 * service yet, saying so. The alternative — hiding them, or worse, showing buttons that quietly
 * do nothing — either hides the requirement or lies about it. See [LibraryFeed] for which of
 * these is false and why.
 */
internal data class LibraryCapabilities(
    val canFavorite: Boolean,
    val canMarkRead: Boolean,
    val canDelete: Boolean,
) {
    companion object {
        val None = LibraryCapabilities(canFavorite = false, canMarkRead = false, canDelete = false)
    }
}

/** The result of a batch action, including "the data layer cannot do this yet". */
internal sealed interface BatchOutcome {
    /**
     * @param skipped rows the action could not touch — e.g. marking a container read needs a page
     *   count, and a container's is unknown until it is opened. Reported rather than swallowed:
     *   silently doing four of five things is worse than saying so.
     */
    data class Applied(val count: Int, val skipped: Int) : BatchOutcome

    data class Unsupported(val reason: String) : BatchOutcome

    data class Failed(val reason: String) : BatchOutcome
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

    /** The whole library, re-emitted as scans land. */
    fun observeBooks(): Flow<List<LibraryBookUi>>

    /** Substring match over series, title and filename. A blank query means the whole library. */
    suspend fun search(query: String): List<LibraryBookUi>

    /** True once at least one storage location has been configured. */
    suspend fun hasLocations(): Boolean

    suspend fun setFavorite(paths: Set<String>, favorite: Boolean): BatchOutcome

    suspend fun setRead(paths: Set<String>, read: Boolean): BatchOutcome

    suspend fun delete(paths: Set<String>): BatchOutcome
}
