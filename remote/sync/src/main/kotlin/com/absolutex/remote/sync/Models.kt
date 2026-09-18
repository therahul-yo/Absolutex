package com.absolutex.remote.sync

/** Which remote reading server a sync account talks to. */
enum class ServerKind {
    KOMGA,
    KAVITA,
}

/** Minimal series identity (required fields only, no defaults on identity fields). */
data class SeriesRef(val id: String, val name: String)

/** Minimal book identity (required fields only, no defaults on identity fields). */
data class BookRef(
    val id: String,
    val name: String,
    val seriesId: String,
    val pageCount: Int,
    /**
     * File size in bytes (BookDto.sizeBytes). Null when the server omits it — such a book can
     * never match a local identity and is left alone, never guessed.
     */
    val sizeBytes: Long? = null,
)

/** One entry of a remote book page listing (required fields only). */
data class PageRef(val number: Int, val fileName: String, val mediaType: String)

/** Read position as reported by a remote server, normalised to Absolutex terms. */
data class RemoteProgress(val page: Int, val completed: Boolean, val updatedAt: Long)

/**
 * Local read position mirrored to a remote server.
 *
 * TODO(sync): map to core:data ReadingProgress (bookId/pageIndex/pageCount/updatedAt) when the reader
 * wiring lands — that needs the lead's files, so this module intentionally does not depend on
 * :core:data (Room/Hilt weight).
 */
data class SyncProgress(val bookId: String, val pageIndex: Int, val pageCount: Int, val updatedAt: Long)

/** Minimal Kavita library identity. */
data class LibraryRef(val id: Int, val name: String)

/**
 * Kavita chapter progress payload. Field names mirror ProgressDto; pageNum is 0-based like the
 * local index (verified: ReaderController.GetImage clamps `page < 0` to 0 and caches by it).
 */
data class KavitaProgress(
    val volumeId: Int,
    val chapterId: Int,
    val pageNum: Int,
    val seriesId: Int,
    val libraryId: Int,
)

/** Kavita JWT session (memory only — see KavitaClient). */
internal data class KavitaSession(val token: String, val refreshToken: String)

/** One Komga Spring-Data page (content + last flag drive accumulation). */
data class KomgaPage<out T>(val items: List<T>, val last: Boolean)

/**
 * A newer remote position that arrived after its book was already open. The reader must never
 * move the page under the user, so this is offered — never applied — for the reader chrome
 * to surface as "Continue at page N from <server>". Null in [SyncRunner.offers] means there
 * is nothing to offer.
 */
data class RemoteProgressOffer(
    val bookId: String,
    val pageIndex: Int,
    val serverId: String,
    val serverLabel: String,
)

/** A Komga book plus its embedded read progress (absent until first read). */
internal data class KomgaBook(val book: BookRef, val progress: RemoteProgress?)
