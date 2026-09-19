package com.absolutex.core.decode

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * DecodeClassifier, pure JVM (docs/decode-oom-policy.md): an OOM is a resource event, a
 * corrupt-input failure is a property of the page, and anything else propagates rather
 * than being laundered into either.
 */
class DecodeClassifierTest {

    @Test
    fun `a successful decode is Decoded`() {
        val outcome = DecodeClassifier.classify { FAKE }
        assertTrue(outcome is DecodeOutcome.Decoded)
    }

    @Test
    fun `a null decoder result is Unreadable`() {
        // Decoders signal some corrupt input by returning null rather than throwing.
        assertTrue(DecodeClassifier.classify { null } is DecodeOutcome.Unreadable)
    }

    @Test
    fun `an OutOfMemoryError is a resource event, never Unreadable`() {
        val outcome = DecodeClassifier.classify { throw OutOfMemoryError("bitmap") }
        assertTrue(outcome is DecodeOutcome.OutOfMemory)
    }

    @Test
    fun `corrupt-input exceptions are Unreadable`() {
        assertTrue(
            DecodeClassifier.classify { throw IOException("truncated") } is DecodeOutcome.Unreadable,
        )
        assertTrue(
            DecodeClassifier.classify { throw IllegalArgumentException("bad jpeg") } is
                DecodeOutcome.Unreadable,
        )
    }

    @Test
    fun `unrelated Errors propagate instead of becoming unreadable pages`() {
        // The policy's point: a broad catch would launder an assertion failure or a
        // StackOverflowError into "this page could not be read", which is false and
        // hides a real bug. These must escape.
        val propagates: (Class<out Throwable>) -> Boolean = { type ->
            try {
                DecodeClassifier.classify {
                    throw type.getDeclaredConstructor(String::class.java).newInstance("x")
                }
                false
            } catch (e: Throwable) {
                e.javaClass == type
            }
        }
        assertTrue(propagates(IllegalStateException::class.java))
        assertTrue(propagates(UnsupportedOperationException::class.java))
    }

    private companion object {
        private val FAKE: PageImage = object : PageImage {
            override val width = 1
            override val height = 1
            override fun decodeBase(targetWidth: Int, targetHeight: Int): android.graphics.Bitmap =
                throw UnsupportedOperationException("not used")
            override fun decodeTile(tile: Tile): android.graphics.Bitmap? = null
            override fun decodeThumbnail(targetEdge: Int): android.graphics.Bitmap? = null
            override fun close() {}
        }
    }
}
