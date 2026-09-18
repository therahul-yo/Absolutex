package com.absolutex.core.scan

import com.absolutex.model.ParsedName
import com.absolutex.source.EntryFilter
import com.absolutex.source.FilenameParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What a scan found: a container file, or a folder of loose images treated as one book (§2).
 *
 * [displayName] is the file or folder's real name, kept alongside [path] because [path] is not
 * always a name a route can be built from: a SAF [path] is a `content://` document Uri whose last
 * segment is a percent-encoded document id, not the filename the user picked. `File(path).name`
 * looked like the filename for a filesystem path and quietly was not one for a SAF path — see
 * [com.absolutex.core.data.LibraryRepository.toEntity], which keys reading progress on this field
 * for exactly that reason.
 */
data class ScannedBook(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val parsed: ParsedName,
    val isImageFolder: Boolean = false,
    /** Images in the folder, for an image-folder book; 0 for a container, whose count needs opening. */
    val imageCount: Int = 0,
)

/** A path the walk decided is worth parsing. Parsing happens on a worker, not in the walk. */
private sealed interface Candidate {
    val file: File

    data class Container(override val file: File) : Candidate
    data class ImageFolder(override val file: File, val images: Int, val bytes: Long) : Candidate
}

/**
 * Walks storage looking for readable books.
 *
 * Budget (§3): 5,000 files in under 15 s, fully parallel, cancellable, never on the UI thread.
 *
 * One producer walks directories and [parallelism] workers parse what it finds, because the two
 * halves cost differently: walking is I/O-bound and serial per directory, while name parsing is
 * CPU-bound and embarrassingly parallel. Doing both in one loop leaves the parse waiting on
 * readdir.
 *
 * Cancellation is ordinary coroutine cancellation — collect in a scope you can cancel. The
 * channel is bounded so a cancelled scan stops promptly instead of draining a backlog.
 */
class LibraryScanner(
    private val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    private val includeHidden: Boolean = false,
) {

    /**
     * @param roots directories to walk. Overlapping roots are fine: a book reached twice is
     *   emitted once, keyed by canonical path — §5.1 deduplicates files shared between locations.
     */
    fun scan(roots: List<File>): Flow<ScannedBook> = channelFlow {
        val candidates = Channel<Candidate>(CHANNEL_CAPACITY)
        val emitted = ConcurrentHashMap.newKeySet<String>()

        val workers = List(parallelism) {
            launch {
                for (candidate in candidates) {
                    val canonical = canonicalOf(candidate.file)
                    if (!emitted.add(canonical)) continue
                    send(parse(candidate))
                }
            }
        }

        // Walking runs on this coroutine; workers drain the channel as it fills.
        val seenDirs = HashSet<String>()
        for (root in roots) walk(root, candidates, seenDirs)
        candidates.close()
        workers.forEach { it.join() }
    }

    private fun parse(candidate: Candidate): ScannedBook = when (candidate) {
        is Candidate.Container -> ScannedBook(
            path = candidate.file.path,
            displayName = candidate.file.name,
            sizeBytes = candidate.file.length(),
            parsed = FilenameParser.parse(candidate.file.path),
        )
        is Candidate.ImageFolder -> ScannedBook(
            path = candidate.file.path,
            displayName = candidate.file.name,
            sizeBytes = candidate.bytes,
            // A folder book has no filename to parse, so the folder name is the whole of it.
            parsed = FilenameParser.parse(candidate.file.path),
            isImageFolder = true,
            imageCount = candidate.images,
        )
    }

    /**
     * Depth-first walk that refuses to revisit a directory.
     *
     * The canonical-path check is not paranoia: a symlink pointing at an ancestor turns the tree
     * into a cycle, and a walk without it recurses until the stack runs out. External drives and
     * network mounts really do contain such links.
     */
    private suspend fun walk(dir: File, out: Channel<Candidate>, seenDirs: HashSet<String>) {
        if (!seenDirs.add(canonicalOf(dir))) return
        val children = dir.listFiles()?.filterNot { shouldSkip(it, includeHidden) } ?: return
        val (subdirs, files) = children.partition { it.isDirectory }

        for (file in files) {
            if (EntryFilter.extensionOf(file.name) in CONTAINER_EXTENSIONS) {
                out.send(Candidate.Container(file))
            }
        }

        // §2: "folder with >= 2 images, no subfolders, treated as a book".
        val images = files.filter { EntryFilter.isPage(it.name) }
        if (subdirs.isEmpty() && images.size >= MIN_IMAGES_FOR_FOLDER_BOOK) {
            out.send(Candidate.ImageFolder(dir, images.size, images.sumOf { it.length() }))
        }

        for (sub in subdirs) walk(sub, out, seenDirs)
    }

    private fun canonicalOf(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    companion object {
        /**
         * Parses one file exactly as a scan of its directory would: a container becomes a book,
         * and a folder at or over the image rule becomes a folder book. Returns null for anything
         * the scanner would not pick up — junk, hidden when those are excluded, or a plain file
         * that is neither a container nor a book folder.
         *
         * Exists for the change stream: an `Added` names one path, and answering it must not
         * re-parse its whole location.
         */
        fun scanFile(file: File, includeHidden: Boolean = false): ScannedBook? {
            if (shouldSkip(file, includeHidden)) return null
            if (file.isDirectory) {
                val children = file.listFiles()?.filterNot { shouldSkip(it, includeHidden) }
                    ?: return null
                val images = children.filter { !it.isDirectory && EntryFilter.isPage(it.name) }
                val hasSubdir = children.any { it.isDirectory }
                if (!hasSubdir && images.size >= MIN_IMAGES_FOR_FOLDER_BOOK) {
                    return ScannedBook(
                        path = file.path,
                        displayName = file.name,
                        sizeBytes = images.sumOf { it.length() },
                        parsed = FilenameParser.parse(file.path),
                        isImageFolder = true,
                        imageCount = images.size,
                    )
                }
                return null
            }
            if (file.isFile && EntryFilter.extensionOf(file.name) in CONTAINER_EXTENSIONS) {
                return ScannedBook(
                    path = file.path,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    parsed = FilenameParser.parse(file.path),
                )
            }
            return null
        }

        /**
         * Everything a scan never looks at, in one place.
         *
         * Junk directories matter as much as junk files: filtering only names lets
         * "__MACOSX/001.cbz" through, because that filename is perfectly innocent.
         *
         * Shared with [LibraryWatcher] so watch and scan never disagree on what to ignore.
         */
        fun shouldSkip(file: File, includeHidden: Boolean): Boolean =
            shouldSkip(file.name, file.isDirectory, includeHidden)

        /** By name, for a tree whose entries are documents rather than files (see SafScanner). */
        fun shouldSkip(name: String, isDirectory: Boolean, includeHidden: Boolean): Boolean {
            if (!includeHidden && name.startsWith(".")) return true
            return if (isDirectory) EntryFilter.isJunkDirectory(name) else EntryFilter.isJunk(name)
        }
        /**
         * Containers worth opening (§2). Kept here rather than in FilenameParser: the parser
         * strips extensions it recognises, this decides what is a book in the first place.
         */
        val CONTAINER_EXTENSIONS = setOf("cbz", "cbr", "cb7", "cbt", "zip", "rar", "7z", "tar", "pdf")

        const val MIN_IMAGES_FOR_FOLDER_BOOK = 2

        /** Bounded so cancellation is prompt: unbounded would keep a dead scan alive. */
        const val CHANNEL_CAPACITY = 256
    }
}
