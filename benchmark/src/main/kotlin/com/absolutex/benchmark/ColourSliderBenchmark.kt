package com.absolutex.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Live slider drags: 120 fps frames during continuous uniform updates, with no page recomposition.
 *
 * [sliderDrag5s] drags the colour chrome's white-balance slider back and forth for ~5 s while
 * FrameTimingMetric records. Each drag only pushes new uniforms to the already-built shader, so
 * any frame overrun here is uniform-update cost, not page-decode cost.
 *
 * Activates once the lead hosts ColourPanel in the reader chrome; until then the slider is
 * absent and the scenario skips. Run with:
 * `tools/run-benchmark.sh 'com.absolutex.benchmark.ColourSliderBenchmark#sliderDrag5s'`.
 */
@RunWith(AndroidJUnit4::class)
class ColourSliderBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private val pkg = "com.absolutex"
    private val book = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"

    // Duplicated from :core:gpu's gpu_temperature string ("White balance"), not depended on:
    // this module deliberately depends on nothing but the benchmark harness.
    private val whiteBalanceLabel = "White balance"

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

    @Test fun sliderDrag5s() = rule.measureRepeated(
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
        val slider = device.wait(Until.findObject(By.textContains(whiteBalanceLabel)), 3_000)
        // The reader chrome is lead-owned and does not host ColourPanel yet: skip, not fail.
        Assume.assumeTrue(
            "colour chrome not wired yet (TODO(lead) at PageCanvas colour param)",
            slider != null,
        )
        val bounds = checkNotNull(slider).visibleBounds
        val y = bounds.centerY()
        val left = bounds.left + bounds.width() * 15 / 100
        val right = bounds.left + bounds.width() * 85 / 100
        // 10 drags, ~500 ms each (ReaderBenchmark: 8 steps is ~40 ms, so 100 steps is ~500 ms).
        repeat(5) {
            device.swipe(left, y, right, y, 100)
            device.swipe(right, y, left, y, 100)
        }
    }
}
