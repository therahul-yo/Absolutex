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
 * Milestone 3 (§4 GPU pipeline): kernel-upscaling cost at 4× with the page at rest.
 *
 * Kernel upscalers (Mitchell, Lanczos) never run on gesture frames — a pinch or pan always
 * draws the hardware tap, and lifting the fingers refines the page with kernel repaints. So the
 * number that matters is not a gesture P99 but the refinement tail: pinch to ~4×, release, let
 * the kernel repaints land, repeat. [refineLanczos4x] does that with Lanczos selected (neutral
 * colour, so the delta over [refinePlatform4x] is the kernel alone, no grading confound);
 * compare P99 and, especially, the max frame — the refinement repaints live in the tail.
 *
 * The pinch count below is calibrated to land at ~4× on the reference corpus (Absolute Batman
 * CBR, ~1988×3057 pages on a 1240-wide viewport; MAX_SCALE clamps at 8×). If the landing zoom
 * differs on the day, adjust the repeat count — the requirement is ≥4× with the release-idle
 * tail captured. Upscaler travels on its own intent extra (Upscaler.EXTRA_UPSCALER); colour on
 * the M1/M2 one. Run one scenario at a time with:
 * `tools/run-benchmark.sh 'com.absolutex.benchmark.UpscaleBenchmark#refineLanczos4x'`.
 * `tools/run-benchmark.sh 'com.absolutex.benchmark.UpscaleBenchmark#refinePlatform4x'`.
 *
 * Screenshot spec for the lead: Absolute Batman CBR, **one interior page with speech bubbles
 * (page 3), at 4× zoom** — not the cover (covers are high-resolution colour art with little
 * lettering, so they hide the softness an upscaler fixes). Capture the same page and zoom with
 * PLATFORM, MITCHELL and LANCZOS selected (upscaler travels on Upscaler.EXTRA_UPSCALER). The
 * speech bubble's text edges are the comparison target — bilinear softens them, Mitchell sharpens
 * with mild ringing, Lanczos sharpens with more ringing.
 */
@RunWith(AndroidJUnit4::class)
class UpscaleBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private val pkg = "com.absolutex"
    private val book = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"

    // Frozen contracts, duplicated not depended on like the other benchmark files:
    // ColourParams.EXTRA_COLOUR and Upscaler.EXTRA_UPSCALER.
    private val extraColour = "com.absolutex.gpu.COLOUR"
    private val extraUpscaler = "com.absolutex.gpu.UPSCALER"

    private fun viewIntent(upscaler: String? = null) = Intent(Intent.ACTION_VIEW).apply {
        setClassName(pkg, "com.absolutex.MainActivity")
        data = Uri.fromFile(File(book))
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (upscaler != null) putExtra(extraUpscaler, upscaler)
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

    /** Pinch-release-idle cycles: gesture frames stay bilinear, the idle tail refines. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.pinchesToFourX() {
        val content = reader()
        repeat(3) {
            content.pinchOpen(0.9f, 150)
            device.waitForIdle()
        }
        device.waitForIdle()
    }

    /** Back to fit for the next cycle: closes overshoot into the MIN_SCALE clamp. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.backToFit() {
        val content = reader()
        repeat(4) {
            content.pinchClose(0.9f, 150)
            device.waitForIdle()
        }
        device.waitForIdle()
    }

    @Test fun refineLanczos4x() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent("lanczos"))
        },
    ) {
        awaitReader()
        repeat(4) {
            pinchesToFourX()
            backToFit()
        }
    }

    @Test fun refineMitchell4x() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent("mitchell"))
        },
    ) {
        awaitReader()
        repeat(4) {
            pinchesToFourX()
            backToFit()
        }
    }

    @Test fun refinePlatform4x() = rule.measureRepeated(
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
        repeat(4) {
            pinchesToFourX()
            backToFit()
        }
    }

    /** Page turns at 4× with each upscaler: the kernel refines on every turn. */
    @Test fun pageTurnLanczos4x() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent("lanczos"))
        },
    ) {
        awaitReader()
        repeat(4) {
            pinchesToFourX()
            device.waitForIdle()
            // LEFT advances (see ReaderBenchmark for why coordinates, not a node handle).
            val y = device.displayHeight / 2
            device.swipe(device.displayWidth * 85 / 100, y, device.displayWidth * 15 / 100, y, 8)
            device.waitForIdle()
        }
    }

    @Test fun pageTurnMitchell4x() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent("mitchell"))
        },
    ) {
        awaitReader()
        repeat(4) {
            pinchesToFourX()
            device.waitForIdle()
            val y = device.displayHeight / 2
            device.swipe(device.displayWidth * 85 / 100, y, device.displayWidth * 15 / 100, y, 8)
            device.waitForIdle()
        }
    }

    @Test fun pageTurnPlatform4x() = rule.measureRepeated(
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
        repeat(4) {
            pinchesToFourX()
            device.waitForIdle()
            val y = device.displayHeight / 2
            device.swipe(device.displayWidth * 85 / 100, y, device.displayWidth * 15 / 100, y, 8)
            device.waitForIdle()
        }
    }
}
