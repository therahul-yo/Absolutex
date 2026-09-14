package com.absolutex.source.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure JVM: no Android types, so this runs without a device. */
class PdfTilingTest {

    @Test fun `scale of one maps points to pixels`() {
        assertEquals(612, PdfTiling.scaledLength(612f, 1f))
        assertEquals(792, PdfTiling.scaledLength(792f, 1f))
    }

    @Test fun `scaling multiplies both axes`() {
        assertEquals(1224, PdfTiling.scaledLength(612f, 2f))
        assertEquals(306, PdfTiling.scaledLength(612f, 0.5f))
    }

    // The rounding direction is load-bearing: a tile grid laid out against a rounded-down
    // page would leave the final row of pixels uncovered.
    @Test fun `fractional extents round up, never down`() {
        assertEquals(101, PdfTiling.scaledLength(100.001f, 1f))
        assertEquals(613, PdfTiling.scaledLength(612f, 1.0001f))
    }

    @Test fun `a page never scales away to nothing`() {
        assertEquals(1, PdfTiling.scaledLength(1f, 0.0001f))
    }

    @Test fun `rejects a non-positive scale`() {
        assertThrows(IllegalArgumentException::class.java) { PdfTiling.scaledLength(612f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { PdfTiling.scaledLength(612f, -1f) }
    }

    @Test fun `rejects a non-finite scale`() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfTiling.scaledLength(612f, Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTiling.scaledLength(612f, Float.POSITIVE_INFINITY)
        }
    }

    @Test fun `rejects a non-positive page extent`() {
        assertThrows(IllegalArgumentException::class.java) { PdfTiling.scaledLength(0f, 1f) }
    }

    @Test fun `accepts a normal tile`() {
        PdfTiling.requireUsableTile(0, 0, 256, 256)
        PdfTiling.requireUsableTile(-10, -10, 10, 10)
    }

    @Test fun `rejects an empty or inverted tile`() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfTiling.requireUsableTile(10, 10, 10, 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTiling.requireUsableTile(10, 10, 20, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTiling.requireUsableTile(20, 20, 10, 10)
        }
    }
}
