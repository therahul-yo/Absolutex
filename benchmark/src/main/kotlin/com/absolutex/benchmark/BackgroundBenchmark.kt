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
 * Milestone 5 (auto background colour): edge sampling rides along with page turns.
 *
 * What the lead compares, against the README's measured page-turn P99 5.2 ms on the reference
 * phone: [pageTurnAutoBackground] samples every page it turns, and sampling piggybacks the crop
 * thumbnail, so zero added frames are expected — any regression here is the sampling, not the draw.
 *
 * The background *animation* benchmark activates once the lead wires the animated background in
 * the reader chrome. The sampling callback already fires pre-settle, so the gesture below (10
 * left-swipes with a wait-for-idle between turns) will capture the animation frames unchanged.
 * Run with: `tools/run-benchmark.sh
 * 'com.absolutex.benchmark.BackgroundBenchmark#pageTurnAutoBackground'`.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private val pkg = "com.absolutex"
    private val book = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"

    // The default path already samples: no intent extras, unlike GpuBenchmark's colour hook.
    private fun viewIntent() = Intent(Intent.ACTION_VIEW).apply {
        setClassName(pkg, "com.absolutex.MainActivity")
        data = Uri.fromFile(File(book))
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.awaitReader() {
        check(device.wait(Until.gone(By.text("Open a comic")), 5_000) != false) {
            "app showed the picker - the book path was not honoured"
        }
        device.waitForIdle()
    }

    @Test fun pageTurnAutoBackground() = rule.measureRepeated(
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
}
