package com.absolutex.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M4 crop cost in the tap-to-first-page path (§3's 250 ms budget).
 *
 * The crop is decided before the first frame: a 96 px thumbnail is decoded from the
 * page bytes, copied to software, and scanned by CropMath.detect — all on the main
 * thread, because the base layer paint waits for `cropDecided`. This benchmark
 * measures the end-to-end cost of that path: tap book → first page fully rendered,
 * with crop enabled vs disabled.
 *
 * The thumbnail decode is a second decode of the page bytes at 96 px (not a copy
 * from the base layer — the base layer is the full-size decode, and the thumbnail
 * is a separate, smaller decode). Both run on the decode dispatcher, but the
 * thumbnail decode blocks the base layer paint because `cropDecided` gates it.
 *
 * Run with:
 *   tools/run-benchmark.sh 'com.absolutex.benchmark.CropStartupBenchmark#tapToFirstPageCropOn'
 *   tools/run-benchmark.sh 'com.absolutex.benchmark.CropStartupBenchmark#tapToFirstPageCropOff'
 *
 * The delta between the two is the crop's cost in the tap-to-first-page path.
 * Trace sections `absx.cropCopy` and `absx.cropDetect` split the cost in Perfetto.
 */
@RunWith(AndroidJUnit4::class)
class CropStartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val pkg = "com.absolutex"
    private val book = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"

    // Frozen contract with CropMath.EXTRA_CROP (duplicated, not depended on).
    private val extraCrop = "com.absolutex.gpu.CROP"

    private fun viewIntent(cropOff: Boolean = false) = Intent(Intent.ACTION_VIEW).apply {
        setClassName(pkg, "com.absolutex.MainActivity")
        data = Uri.fromFile(File(book))
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (cropOff) putExtra(extraCrop, "0")
    }

    /**
     * Tap book → first page rendered, with crop enabled (the default).
     * This is the path a user actually hits: the thumbnail decode + copy + detect
     * all run before the first paint.
     */
    @Test
    fun tapToFirstPageCropOn() = benchmarkRule.measureRepeated(
        packageName = pkg,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 20,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait(viewIntent(cropOff = false))
    }

    /**
     * Same path with crop disabled via the benchmark extra. The base layer paints
     * immediately — no thumbnail decode, no copy, no detect. The delta between
     * this and [tapToFirstPageCropOn] is the crop's cost in the tap-to-first-page
     * path.
     */
    @Test
    fun tapToFirstPageCropOff() = benchmarkRule.measureRepeated(
        packageName = pkg,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 20,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait(viewIntent(cropOff = true))
    }
}
