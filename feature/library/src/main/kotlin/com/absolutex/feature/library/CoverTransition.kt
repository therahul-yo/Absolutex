package com.absolutex.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The shared-element key for a book's cover. The book's [path][LibraryBookUi.path] is the one
 * identity every browse presentation agrees on — grid cards, hero art and list rows all key
 * their items by it — so the reader destination keys by the same string and the two ends meet.
 */
fun coverTransitionKey(path: String): String = "cover:$path"

/**
 * Whether a card's cover stays drawn. The open book's card hides while its reader sheet is up,
 * so the shared element is never drawn twice — once in the grid, once flying to the reader.
 * Every other card is untouched, which is also what keeps the hidden grid from recomposing its
 * art while nobody looks at it.
 */
fun isCoverSourceVisible(openCoverPath: String?, bookPath: String): Boolean = openCoverPath != bookPath

/**
 * Whether the reader sheet's cover hero has done its job: the book was seen loading and has
 * since settled with real content (pages, or the text reader) and no error. The hero covers
 * the decoding window, then gets out of the way of the first page.
 */
fun isCoverHeroReady(
    seenLoading: Boolean,
    loading: Boolean,
    error: String?,
    pageCount: Int,
    textEpub: Boolean,
): Boolean = seenLoading && !loading && error == null && (pageCount > 0 || textEpub)

/**
 * The app's end of the cover transition. Defined here so the library never depends on the app
 * (or the reader) for it; provided by `:app`, which owns the [SharedTransitionLayout] both ends
 * live under. Null in previews and unit tests, where covers simply stay put.
 */
interface CoverTransitionHost {
    /**
     * The shared-element modifier for one cover. [visible] is caller-managed: the source card
     * hides exactly when its book opens, the reader hero shows exactly while it covers loading
     * (and the close fade), and each same-frame handoff is what runs the flight in either
     * direction. Neither end uses an AnimatedVisibility, so a browsing grid pays for one
     * modifier per cover and nothing per card.
     */
    @Composable
    fun coverModifier(path: String, visible: Boolean): Modifier

    /**
     * A card was tapped. Only a card that shows its art flies: a document whose cover is hidden
     * for privacy must not have its first page rendered by the reader end instead.
     */
    fun opened(path: String, showsCover: Boolean)

    /** Whether the book at [path] was opened from a card whose art may fly. Snapshot state. */
    fun flies(path: String): Boolean
}

/**
 * Null until `:app` provides the host above its library-and-reader composition. Library cards
 * read it through [sharedCoverElement] and draw normally without it.
 */
val LocalCoverTransitionHost = staticCompositionLocalOf<CoverTransitionHost?> { null }

/**
 * The shared-element modifier for a card cover, or [Modifier] untouched when no host is
 * provided. Applied to [BookCover]'s own modifier, so the flying rect is the art itself —
 * the card chrome (title, pill, progress) stays behind and the cover grows out of the card.
 */
@Composable
internal fun Modifier.sharedCoverElement(openCoverPath: String?, book: LibraryBookUi): Modifier {
    val host = LocalCoverTransitionHost.current ?: return this
    if (!book.showCover) return this
    return then(host.coverModifier(book.path, isCoverSourceVisible(openCoverPath, book.path)))
}
