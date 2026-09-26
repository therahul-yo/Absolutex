package com.absolutex.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [StagedBytes] on its own: the generation guard, the bound, and the short-result rule.
 *
 * These cannot be reached through [PrefetchEngine]'s tests, which drive it through a settle
 * and never control the generation directly.
 */
class StagedBytesTest {

    private fun bytes(vararg pages: Int) = pages.associateWith { byteArrayOf(1) }

    @Test
    fun `the current generation writes`() {
        val staged = StagedBytes()
        staged.clear(generation = 2)
        staged.put(listOf(1, 2), bytes(1, 2), generation = 2)
        assertEquals(2, staged.size)
        assertEquals(1, staged.take(1)?.size)
    }

    @Test
    fun `a stale generation never writes, and a current one does`() {
        // The generation guard, at the level it is enforced. A test for the ATOMICITY of
        // put-vs-clear needs put's check and write separated by a scheduler interleaving,
        // which this class does not expose: there is no hook between them, and a racing
        // two-thread test with 2,000 attempts did not reach the window even once -- the
        // write and the compare are adjacent, so the JIT and the monitor keep them
        // together. Reported rather than faked: the guard is proven by the stale-generation
        // case below, and the atomicity is a structural property of holding the check
        // inside the lock, not something a JVM test here can observe.
        val staged = StagedBytes()
        staged.clear(generation = 2)
        staged.put(listOf(1, 2), bytes(1, 2), generation = 1)   // stale: must be dropped
        assertEquals("a walk for generation 1 must not write into generation 2", 0, staged.size)
        assertNull(staged.take(1))

        staged.put(listOf(1, 2), bytes(1, 2), generation = 2)   // current: must be kept
        assertEquals(2, staged.size)
        assertEquals(1, staged.take(1)?.size)
    }

    @Test
    fun `a short result stages nothing`() {
        val staged = StagedBytes()
        staged.put(listOf(1, 2, 3), bytes(1, 2), generation = 0)
        assertEquals("a short walk must stage nothing, not a partial window", 0, staged.size)
    }

    @Test
    fun `a new window replaces the previous one rather than merging`() {
        val staged = StagedBytes()
        // Nothing is claimed from this window -- that is the case that leaked. Under a merge
        // its pages would still be here after the next window, holding compressed bytes no
        // decode wanted and no budget accounts for.
        staged.put(listOf(1, 2, 3), bytes(1, 2, 3), generation = 0)
        assertEquals(3, staged.size)

        staged.put(listOf(5, 6), bytes(5, 6), generation = 0)
        assertEquals("only the current window may be live", 2, staged.size)
        assertNull("the old window's unclaimed page must be gone, not merged", staged.take(1))
        assertEquals(1, staged.take(5)?.size)
    }
}
