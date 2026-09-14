package com.absolutex.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The §3 page-turn budget: frame time under 8.3 ms sustained at 120 Hz, zero dropped frames
 * across a scripted run. The brief calls this the single most important number in the project.
 *
 * PRECONDITION: the app must already hold a persisted SAF grant for a book, so launching
 * resumes straight into the reader with no picker. Open a comic once by hand first; the grant
 * and the last-book URI both survive the kill-and-relaunch the benchmark performs.
 * If that has not been done, the run aborts rather than silently measuring an empty screen.
 */
@RunWith(AndroidJUnit4::class)
class ReaderBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private val pkg = "com.absolutex"

    @Test fun pageTurn() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        // Resume must have landed us in the reader, not on the picker.
        check(device.wait(Until.gone(By.text("Open a comic")), 5_000) != false) {
            "app opened the picker - no book is persisted, see the class doc"
        }
        device.waitForIdle()

        val content = device.findObject(By.pkg(pkg).depth(0))
        repeat(20) {
            // Fling, not swipe: this measures the settle animation, which is where jank shows.
            content.setGestureMargin(device.displayWidth / 8)
            content.fling(Direction.RIGHT)
            device.waitForIdle()
        }
    }

    @Test fun zoom() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        device.waitForIdle()
        val content = device.findObject(By.pkg(pkg).depth(0))
        repeat(6) {
            content.pinchOpen(0.75f, 100)
            device.waitForIdle()
            content.pinchClose(0.75f, 100)
            device.waitForIdle()
        }
    }
}
