package com.absolutex.core.decode

/**
 * The budget invariant: `tile + resident` must fit inside the budget.
 *
 * Extracted from [PrefetchEngine] because the policy and the bookkeeping are separable, and
 * inlining both put the engine three functions over detekt's limit. The rule is unchanged and
 * still the one the tests pin: evict farthest from the settled page first, never the settled
 * or protected pages, and stop when a round frees nothing — which is what makes an
 * over-budget state a reportable condition instead of an infinite loop.
 *
 * Every mutation goes through the engine's own [synchronized] monitor on [resident], so a
 * landing that is mid-reconcile still sees a consistent set.
 */
internal class EvictionPolicy(
    private val resident: LinkedHashMap<Int, Long>,
    private val tileBytes: () -> Long,
    private val budgetBytes: () -> Long,
    private val onEvicted: (page: Int, bytes: Long) -> Unit,
) {

    /**
     * Restores the invariant: evict farthest-first until tile + resident fits.
     *
     * @param settled the page the reader is on, never evictable
     * @param protect a page being decoded right now, never evictable
     * @param direction which way the reader is moving, so "far" means away from the next read
     */
    fun reconcile(settled: Int, protect: Int, direction: Int) {
        var over = tileBytes() + residentBytes() - budgetBytes()
        var canEvict = true
        while (over > 0 && canEvict) {
            val order = candidates(settled, protect, direction)
            val freed = if (order.isEmpty()) -1 else evictOne(order)
            if (freed <= 0) canEvict = false else over -= freed
        }
    }

    /**
     * Evicts until an incoming [incoming]-byte page would fit, and reports whether it does.
     *
     * The same rule [reconcile] applies, with a size to make room for instead of an
     * overage. Returns false when eviction cannot free enough — a page that will not fit is
     * deferred to the next settle rather than evicted-for-nothing, which is what keeps a
     * page that is too big for the budget from emptying the resident set on every attempt.
     */
    fun makeRoom(page: Int, protect: Int, direction: Int, incoming: Long): Boolean {
        while (tileBytes() + residentBytes() + incoming > budgetBytes()) {
            val order = candidates(page, protect, direction)
            if (order.isEmpty() || evictOne(order) <= 0) break
        }
        return tileBytes() + residentBytes() + incoming <= budgetBytes()
    }

    private fun residentBytes(): Long = synchronized(resident) { resident.values.sum() }

    /** Eviction candidates, farthest from the reader first, never the protected pages. */
    private fun candidates(settled: Int, protect: Int, direction: Int): List<Int> =
        PrefetchPlanner.evictOrder(
            synchronized(resident) {
                resident.keys.filter { it != settled && it != protect }
            }.toSet(),
            settled,
            direction,
        )

    /** Drops the first candidate and reports it through [onEvicted]; returns bytes freed. */
    private fun evictOne(order: List<Int>): Long {
        val victim = order.firstOrNull() ?: return 0
        val bytes = synchronized(resident) { resident.remove(victim) } ?: return 0
        onEvicted(victim, bytes)
        return bytes
    }
}
