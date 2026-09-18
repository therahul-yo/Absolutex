package com.absolutex.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Milestone 4 (§4 smart border crop): the per-tile intersection cost, with detection excluded.
 *
 * What the lead compares, against the README's measured page-turn P99 5.2 ms on the reference
 * phone (OnePlus 11R, 120 Hz): detection runs once per page at load (traced as `absx.cropDetect`
 * on a ~96 px thumbnail off the main thread), so any frame delta between the two scenarios below
 * is the per-tile intersection math in the draw path, not detection.
 *
 * - [pageTurnCropOn] sends no extras: crop defaults on, so this measures the real path.
 * - [pageTurnCropOff] sends `"0"`, which disables cropping for the run.
 *
 * Cropping travels on the launch intent (see `CropMath.EXTRA_CROP`), which PageCanvas honours as
 * a benchmark hook the same way the colour correction does. Run one scenario at a time with:
 * `tools/run-benchmark.sh 'com.absolutex.benchmark.CropBenchmark#pageTurnCropOn'`.
 * `tools/run-benchmark.sh 'com.absolutex.benchmark.CropBenchmark#pageTurnCropOff'`.
 */
@RunWith(AndroidJUnit4::class)
class CropBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private val pkg = "com.absolutex"
    private val book = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"

    // Frozen contract with CropMath.EXTRA_CROP (duplicated, not depended on: this module
    // deliberately depends on nothing but the benchmark harness).
    private val extraCrop = "com.absolutex.gpu.CROP"

    private fun viewIntent(crop: String? = null) = Intent(Intent.ACTION_VIEW).apply {
        setClassName(pkg, "com.absolutex.MainActivity")
        data = Uri.fromFile(File(book))
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (crop != null) putExtra(extraCrop, crop)
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.awaitReader() {
        check(device.wait(Until.gone(By.text("Open a comic")), 5_000) != false) {
            "app showed the picker - the book path was not honoured"
        }
        device.waitForIdle()
    }

    @Test fun pageTurnCropOn() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent())
        },
    ) {
        awaitReader()
        repeat(10) {
            // LEFT advances (see ReaderBenchmark for why coordinates, not a node handle).
            val y = device.displayHeight / 2
            device.swipe(device.displayWidth * 85 / 100, y, device.displayWidth * 15 / 100, y, 8)
            device.waitForIdle()
        }
    }

    @Test fun pageTurnCropOff() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent("0"))
        },
    ) {
        awaitReader()
        repeat(10) {
            val y = device.displayHeight / 2
            device.swipe(device.displayWidth * 85 / 100, y, device.displayWidth * 15 / 100, y, 8)
            device.waitForIdle()
        }
    }
}
