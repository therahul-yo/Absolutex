package com.absolutex.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The §3 page-turn budget: frame time under 8.3 ms sustained at 120 Hz, zero dropped frames
 * across a scripted run. The brief calls this the single most important number in the project.
 *
 * The book is opened by PATH through an ACTION_VIEW intent, not through SAF.
 *
 * An earlier version relied on the app resuming a persisted SAF grant. That cannot work:
 * Gradle's connectedAndroidTest uninstalls the target APK after each run, which takes the
 * DataStore entry and the persisted Uri permission with it, so every run found the picker.
 * A path also removes the picker from the measurement entirely, which is what we want —
 * this benchmark measures page turns, not SAF.
 *
 * The corpus must be staged in the app's own external files dir before running; see
 * tools/run-benchmark.sh. The test is skipped, not failed, when it is absent.
 */
@RunWith(AndroidJUnit4::class)
class ReaderBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private val pkg = "com.absolutex"
    private val book = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"

    private fun viewIntent() = Intent(Intent.ACTION_VIEW).apply {
        setClassName(pkg, "com.absolutex.MainActivity")
        data = Uri.fromFile(File(book))
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    @Test fun pageTurn() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            assumeTrue("corpus not staged at $book", File(book).exists())
            pressHome()
            startActivityAndWait(viewIntent())
        },
    ) {
        // Must be in the reader, not the picker — otherwise we would be measuring a blank
        // screen and reporting it as a flawless score.
        check(device.wait(Until.gone(By.text("Open a comic")), 5_000) != false) {
            "app showed the picker - the book path was not honoured"
        }
        device.waitForIdle()

        val content = device.findObject(By.pkg(pkg).depth(0))
        // Margin set once: inside the loop it just repeats work between measured gestures.
        content.setGestureMargin(device.displayWidth / 8)
        repeat(10) {
            // LEFT advances. Direction.RIGHT scrolls content rightward, i.e. to the PREVIOUS
            // page — from a resumed mid-book position that walks back to page 0 and then
            // measures an idle screen, which reads as a perfect score for doing nothing.
            content.fling(Direction.LEFT)
            device.waitForIdle()
        }
    }

    @Test fun zoom() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            assumeTrue("corpus not staged at $book", File(book).exists())
            pressHome()
            startActivityAndWait(viewIntent())
        },
    ) {
        device.waitForIdle()
        val content = device.findObject(By.pkg(pkg).depth(0))
        repeat(4) {
            content.pinchOpen(0.75f, 100)
            device.waitForIdle()
            content.pinchClose(0.75f, 100)
            device.waitForIdle()
        }
    }

    /**
     * Phase 4: COLD startup — process creation through first frame (§3's "tap book ->
     * first page rendered < 250 ms" budget starts here). WARM runs above hide exactly this
     * cost, so without a COLD test a regression in Application.onCreate/Hilt init would
     * pass the suite while blowing the launch budget on a real tap.
     */
    @Test fun coldStartup() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            assumeTrue("corpus not staged at $book", File(book).exists())
            pressHome()
        },
    ) {
        startActivityAndWait(viewIntent())
        check(device.wait(Until.gone(By.text("Open a comic")), 5_000) != false) {
            "app showed the picker - the book path was not honoured"
        }
    }

    /**
     * Phase 4: sustained pinch — back-to-back gestures with NO waitForIdle between them.
     * The discrete [zoom] test above lets the render thread drain after every gesture, so it
     * measures settle quality, not continuous-gesture jank. A real pinch-zoom holds the
     * gesture stream open; this keeps it open and lets FrameTimingMetric catch the overruns
     * that only appear when tiles decode under a live gesture.
     */
    @Test fun sustainedPinch() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            assumeTrue("corpus not staged at $book", File(book).exists())
            pressHome()
            startActivityAndWait(viewIntent())
        },
    ) {
        device.waitForIdle()
        val content = device.findObject(By.pkg(pkg).depth(0))
        content.setGestureMargin(device.displayWidth / 8)
        // Deliberately no waitForIdle inside: the next gesture must land mid-render.
        repeat(8) { i ->
            if (i % 2 == 0) content.pinchOpen(0.9f, 50) else content.pinchClose(0.9f, 50)
        }
    }
}
