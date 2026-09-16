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
 * Milestone 1 (§4 GPU pipeline): the shader path, with zero cost when off.
 *
 * What the lead compares, against the README's measured numbers on the reference phone
 * (OnePlus 11R, 120 Hz): page turn P99 5.2 ms, sustained pinch P99 6.5 ms.
 *
 * - [pageTurnNeutral] must land within noise of ReaderBenchmark.pageTurn: correction off runs
 *   the pre-shader draw calls, so any regression here is the plumbing, not the shader.
 * - [pageTurnCorrected] and [pinchCorrected] measure the same gestures with a live non-neutral
 *   correction (brightness +0.15, contrast 1.1, saturation 1.25 — visibly graded, mid-tones
 *   kept in gamut per ColourMathTest). Their delta over the neutral runs is the shader's cost.
 *
 * Correction travels on the launch intent (ColourParams.EXTRA_COLOUR), which PageCanvas honours
 * as a benchmark hook until milestone 2 wires RenderingPrefs. Run one scenario at a time with:
 * `tools/run-benchmark.sh 'com.absolutex.benchmark.GpuBenchmark#pageTurnNeutral'`.
 */
@RunWith(AndroidJUnit4::class)
class GpuBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private val pkg = "com.absolutex"
    private val book = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"

    // Frozen contract with ColourParams.EXTRA_COLOUR (duplicated, not depended on: this module
    // deliberately depends on nothing but the benchmark harness).
    private val extraColour = "com.absolutex.gpu.COLOUR"
    private val corrected = "b=0.15,c=1.1,s=1.25"

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

    @Test fun pageTurnNeutral() = rule.measureRepeated(
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

    @Test fun pageTurnCorrected() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent(corrected))
        },
    ) {
        awaitReader()
        repeat(10) {
            val y = device.displayHeight / 2
            device.swipe(device.displayWidth * 85 / 100, y, device.displayWidth * 15 / 100, y, 8)
            device.waitForIdle()
        }
    }

    @Test fun pinchCorrected() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent(corrected))
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
