package com.absolutex.core.data

/**
 * The rows a scan of one root is allowed to sweep: the root's own row, and rows strictly beneath it.
 *
 * "Beneath" is decided on a path-segment boundary, not on a raw string prefix. Both shapes of
 * [LibraryBook.path] share the rule, because both put a `/` between a root and everything under it:
 *
 *  - a filesystem path: `/sd/Comics` owns `/sd/Comics/x.cbz`, not `/sd/Comics-old/x.cbz`;
 *  - a SAF document Uri: the granted tree `content://auth/tree/primary%3AComics` owns
 *    `content://auth/tree/primary%3AComics/document/primary%3AComics%2Fx.cbz`, and a sibling grant
 *    `…/tree/primary%3AComics2` does not. A `/` inside a document id is percent-encoded (`%2F`), so
 *    a tree granted on a sub-folder is a different root, never a "child" of its parent's tree Uri.
 *
 * The root's own row is included because a folder of loose images that is itself the scan root is
 * recorded under the root's path (see `SafScanner`).
 *
 * Nested roots (`/sd` and `/sd/Comics` both registered) behave deterministically: scanning the outer
 * root sweeps everything under it, including rows the inner root contributed, and rescanning the
 * inner root never touches the outer root's other rows. The outer walk revisits the inner folder,
 * so its live rows are refreshed rather than swept.
 *
 * Matching is done in SQL with `substr`, not `LIKE`: `LIKE` treats `%` and `_` as wildcards
 * (`%` is in every percent-encoded SAF Uri, `_` is in ordinary folder names) and is
 * case-insensitive for ASCII, so `/sd/my_comics` would also claim `/sd/MyXcomics`.
 */
internal data class ScanScope(val root: String, val childPrefix: String) {
    companion object {
        /** Trailing slashes are dropped so `/sd/Comics/` and `/sd/Comics` are the same root. */
        fun of(root: String): ScanScope {
            require(root.isNotEmpty()) { "a scan root must not be empty" }
            val trimmed = root.trimEnd('/')
            return ScanScope(root = trimmed, childPrefix = "$trimmed/")
        }
    }
}

/**
 * Deletes rows under [root] that a scan with id [scanId] did not see, and nothing outside [root].
 * See [ScanScope] for what "under" means.
 */
suspend fun LibraryDao.deleteStaleIn(root: String, scanId: Long): Int {
    val scope = ScanScope.of(root)
    return deleteStaleUnder(scope.root, scope.childPrefix, scanId)
}
