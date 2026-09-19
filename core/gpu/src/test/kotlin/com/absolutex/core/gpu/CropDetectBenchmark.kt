package com.absolutex.core.gpu

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Times [CropMath.detect] on a synthetic 96×96 thumbnail — the exact size the reader
 * uses for border-crop detection (§4, milestone 4).
 *
 * This is the pure-Kotlin detection cost, separate from the hardware-to-software
 * thumbnail copy (traced as `absx.cropCopy` on the device). The copy is the expensive
 * half; this pins the algorithm's own cost so a regression in the scan shows up here
 * without a device.
 *
 * The synthetic page is a white field with a 10 px black margin on every side —
 * the worst case for the scan, since it walks the full margin on all four edges
 * before rejecting.
 */
class CropDetectBenchmark {

    @Test
    fun `detection on a 96x96 thumbnail with margins`() {
        val w = 96
        val h = 96
        val margin = 10
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x !in margin until (w - margin) || y !in margin until (h - margin)) {
                0x000000 // black margin
            } else {
                0xFFFFFF.toInt() // white interior
            }
        }

        // Warmup.
        repeat(100) { CropMath.detect(pixels, w, h) }

        // Timed runs.
        val runs = 1000
        val start = System.nanoTime()
        repeat(runs) { CropMath.detect(pixels, w, h) }
        val elapsed = System.nanoTime() - start
        val perCall = elapsed / runs / 1_000_000.0

        println("CropDetectBenchmark: $runs runs, ${perCall} ms/call (96×96, 10 px margins)")
        assertTrue("detection should be under 1 ms per call", perCall < 1.0)
    }

    @Test
    fun `detection on a full-bleed page (no margins)`() {
        val w = 96
        val h = 96
        val pixels = IntArray(w * h) { 0xFFFFFF.toInt() } // all white

        repeat(100) { CropMath.detect(pixels, w, h) }

        val runs = 1000
        val start = System.nanoTime()
        repeat(runs) { CropMath.detect(pixels, w, h) }
        val elapsed = System.nanoTime() - start
        val perCall = elapsed / runs / 1_000_000.0

        println("CropDetectBenchmark: $runs runs, ${perCall} ms/call (96×96, full-bleed)")
        assertTrue("detection should be under 1 ms per call", perCall < 1.0)
    }

    @Test
    fun `detection on a noisy page (JPEG-like)`() {
        val w = 96
        val h = 96
        // White with ±8 per-channel noise — exercises the tolerance path.
        val pixels = IntArray(w * h) { i ->
            val noise = (i * 7919 % 17) - 8 // deterministic pseudo-noise in [-8, 8]
            val r = (255 + noise).coerceIn(0, 255)
            val g = (255 + noise).coerceIn(0, 255)
            val b = (255 + noise).coerceIn(0, 255)
            (r shl 16) or (g shl 8) or b
        }

        repeat(100) { CropMath.detect(pixels, w, h) }

        val runs = 1000
        val start = System.nanoTime()
        repeat(runs) { CropMath.detect(pixels, w, h) }
        val elapsed = System.nanoTime() - start
        val perCall = elapsed / runs / 1_000_000.0

        println("CropDetectBenchmark: $runs runs, ${perCall} ms/call (96×96, noisy)")
        assertTrue("detection should be under 1 ms per call", perCall < 1.0)
    }
}
