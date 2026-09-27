package com.absolutex.core.decode

/**
 * Holds the compressed bytes a single prefetch window read in one archive walk, waiting for
 * each page's decode to claim them.
 *
 * Extracted from [PrefetchEngine] because the window staging is a unit with its own
 * invariants, and inlining it put the class two functions over detekt's limit. The
 * invariants it owns are the point:
 *
 *  - **Bounded.** A batch writes only its own window's pages, and only when every requested
 *    page came back. A short result stages nothing, because a partial window would leave the
 *    missing pages reading individually anyway while claiming the window was batched.
 *  - **Consumed once.** [take] removes on claim, so a page cannot be held here after the
 *    image that owns it exists. Without that, these bytes and the resident set's would be
 *    two copies of the same page — exactly the duplication the §3 budget exists to prevent.
 *  - **Book-scoped.** [clear] runs on a book switch. A decode claiming staged bytes after a
 *    drop would decode the OLD book's page and bill the NEW book for it: the same mistake
 *    the engine's generation guard prevents for landings, in a field the guard does not
 *    cover.
 */
internal class StagedBytes {

    private val staged = mutableMapOf<Int, ByteArray>()

    /**
     * The book generation these bytes belong to, and the authority for whether a write is
     * allowed.
     *
     * Held here, not read from the engine at the call site, because the check and the write
     * have to be ONE critical section. When the engine compared a generation and then called
     * `put` as two steps, a `clear` landing between them produced exactly the bug this class
     * now prevents: the guard passed, and the old book's bytes were written into the new
     * book's map a moment after that map was emptied. Owning the generation means `clear` and
     * a stale `put` cannot interleave at all.
     */
    private var generation = 0

    /** Pages staged and not yet claimed. Lets a test assert the map drained. */
    val size: Int get() = synchronized(staged) { staged.size }

    /**
     * Replaces the map's contents with [pages] → [bytes], or writes nothing at all when the
     * result is short, null, or an exception. A failure here is never a failure to the pages:
     * they simply decode individually, which is the pre-existing behaviour.
     *
     * REPLACES rather than merges, which is what bounds this map. A merge would keep entries
     * from an earlier window that no decode ever claimed — pages that were evicted, or
     * decodes that were cancelled — and the map would then grow with every window for the
     * life of the book, holding compressed bytes no budget accounts for. Only the current
     * window's pages are ever live here.
     *
     * [generation] is the book generation the caller captured when the walk was launched. A
     * value that is not [generation] is a walk for a book that has since closed, and is
     * dropped under this same lock as the check.
     */
    fun put(pages: List<Int>, bytes: Map<Int, ByteArray?>?, generation: Int) {
        if (bytes == null || bytes.size != pages.size) return
        synchronized(staged) {
            if (generation != this.generation) return
            staged.clear()
            bytes.forEach { (page, payload) -> if (payload != null) staged[page] = payload }
        }
    }

    /**
     * Empties the map and advances to [generation], atomically with respect to [put].
     *
     * Called on a book switch, from the same lock a write takes.
     */
    fun clear(generation: Int) = synchronized(staged) {
        this.generation = generation
        staged.clear()
    }

    /** [page]'s bytes, removing them. Null for a page outside the window, or already claimed. */
    fun take(page: Int): ByteArray? = synchronized(staged) { staged.remove(page) }

}
