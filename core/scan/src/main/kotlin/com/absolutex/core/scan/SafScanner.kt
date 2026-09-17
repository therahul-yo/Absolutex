package com.absolutex.core.scan

import com.absolutex.source.EntryFilter
import com.absolutex.source.FilenameParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** One entry of a document tree, as a scan needs it. */
data class TreeEntry(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
)

/**
 * A document tree's children, abstracted away from `DocumentsContract`.
 *
 * The walk is then a pure function over this, testable with a map instead of a provider: the parts
 * that go wrong in a scan are the ordering, the junk filter and the recursion, not the cursor.
 */
fun interface DocumentTree {
    fun children(documentUri: String): List<TreeEntry>
}

/**
 * Walks a SAF tree the way [LibraryScanner] walks a directory (§5.1).
 *
 * SAF, not paths, because since Android 11 an app cannot read arbitrary storage by path at all:
 * a folder the user picked is reachable only through the tree Uri they granted. What comes back
 * is the same [ScannedBook] the filesystem scan produces, so the library cannot tell which route
 * found a book — and `BookIdentity` matches a book to its progress either way.
 */
object SafScanner {

    /** Depth guard: a tree that reports itself as its own child would otherwise never end. */
    private const val MAX_DEPTH = 16

    /**
     * Streams books as the walk finds them, rather than collecting the whole tree into a list
     * first: a large or slow provider (a big SD card, a cloud-backed DocumentsProvider) should
     * fill the library progressively, the way [LibraryScanner]'s Flow already does, and a
     * cancelled walk should leave behind whatever it already found instead of losing it all to
     * one final, all-or-nothing return.
     */
    fun scan(root: TreeEntry, tree: DocumentTree, includeHidden: Boolean = false): Flow<ScannedBook> = flow {
        val seen = HashSet<String>()
        walk(root, tree, includeHidden, 0, seen)
    }

    private suspend fun FlowCollector<ScannedBook>.walk(
        dir: TreeEntry,
        tree: DocumentTree,
        includeHidden: Boolean,
        depth: Int,
        seen: MutableSet<String>,
    ) {
        // Checked per directory, not left to emit() alone: a subtree with no books of its own
        // (many empty or junk-only folders in a row) would otherwise run uninterrupted between
        // one emission and the next.
        currentCoroutineContext().ensureActive()
        if (depth > MAX_DEPTH || !seen.add(dir.uri)) return
        val children = tree.children(dir.uri)
            .filterNot { LibraryScanner.shouldSkip(it.name, it.isDirectory, includeHidden) }
        val (dirs, files) = children.partition { it.isDirectory }
        for (file in files) {
            if (EntryFilter.extensionOf(file.name) in LibraryScanner.CONTAINER_EXTENSIONS) {
                emit(
                    ScannedBook(
                        path = file.uri,
                        sizeBytes = file.sizeBytes,
                        parsed = FilenameParser.parse(file.name),
                    ),
                )
            }
        }
        // A folder of loose images is a book, and then it is not also a folder to descend into.
        val images = files.filter { EntryFilter.isPage(it.name) }
        if (dirs.isEmpty() && images.size >= LibraryScanner.MIN_IMAGES_FOR_FOLDER_BOOK) {
            emit(
                ScannedBook(
                    path = dir.uri,
                    sizeBytes = images.sumOf { it.sizeBytes },
                    parsed = FilenameParser.parse(dir.name),
                    isImageFolder = true,
                    imageCount = images.size,
                ),
            )
            return
        }
        for (child in dirs) walk(child, tree, includeHidden, depth + 1, seen)
    }
}
