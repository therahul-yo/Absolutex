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
 * Milestone 2 (§4 GPU pipeline): full-correction cost with every shader stage live.
 *
 * What the lead compares, against the README's measured numbers on the reference phone
 * (OnePlus 11R, 120 Hz): page turn P99 5.2 ms, sustained pinch P99 6.5 ms.
 *
 * - [pageTurnFull] P99 vs GpuBenchmark.pageTurnNeutral (plumbing) and
 *   GpuBenchmark.pageTurnCorrected (brightness/contrast/saturation only): the delta over the
 *   corrected run is the cost of the white-balance, vibrance and gamma stages.
 * - [pinchFull] P99 vs GpuBenchmark.pinchCorrected and the README pinch 6.5 ms: same delta,
 *   under a live gesture stream.
 *
 * Correction travels on the launch intent, like GpuBenchmark. Run one scenario at a time with:
 * `tools/run-benchmark.sh 'com.absolutex.benchmark.ColourBenchmark#pageTurnFull'`.
 * `tools/run-benchmark.sh 'com.absolutex.benchmark.ColourBenchmark#pinchFull'`.
 */
@RunWith(AndroidJUnit4::class)
class ColourBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private val pkg = "com.absolutex"
    private val book = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"

    // Frozen contract with ColourParams.EXTRA_COLOUR (duplicated, not depended on: this module
    // deliberately depends on nothing but the benchmark harness).
    private val extraColour = "com.absolutex.gpu.COLOUR"

    // Every shader stage live: brightness, contrast, saturation, white balance (t + a),
    // vibrance, combined gamma and per-channel gammas. Non-neutral by a clear margin.
    private val full = "b=0.1,c=1.15,s=1.2,t=0.4,a=0.8,v=0.6,g=1.05,gr=1.0,gg=1.0,gb=0.95"

    private fun viewIntent(colour: String? = null) = Intent(Intent.ACTION_VIEW).apply {
        setClassName(pkg, "com.absolutex.MainActivity")
        data = Uri.fromFile(File(book))
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (colour != null) putExtra(extraColour, colour)
    }

    /** Freshly resolved reader node, with a gesture margin so pinches stay inside the page. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.reader() =
        checkNotNull(device.wait(Until.findObject(By.pkg(pkg)), 5_000)) { "reader not on screen" }
            .apply { setGestureMargin(device.displayWidth / 8) }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.awaitReader() {
        check(device.wait(Until.gone(By.text("Open a comic")), 5_000) != false) {
            "app showed the picker - the book path was not honoured"
        }
        device.waitForIdle()
    }

    @Test fun pageTurnFull() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent(full))
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

    @Test fun pinchFull() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent(full))
        },
    ) {
        awaitReader()
        repeat(4) {
            reader().pinchOpen(0.75f, 100)
            device.waitForIdle()
            reader().pinchClose(0.75f, 100)
            device.waitForIdle()
        }
    }
}
