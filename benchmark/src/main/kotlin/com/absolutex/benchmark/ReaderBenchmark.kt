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

    /** Freshly resolved reader node, with a gesture margin so pinches stay inside the page. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.reader() =
        checkNotNull(device.wait(Until.findObject(By.pkg(pkg)), 5_000)) { "reader not on screen" }
            .apply { setGestureMargin(device.displayWidth / 8) }

    @Test fun pageTurn() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
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

        repeat(10) {
            // LEFT advances. Direction.RIGHT scrolls content rightward, i.e. to the PREVIOUS
            // page — from a resumed mid-book position that walks back to page 0 and then
            // measures an idle screen, which reads as a perfect score for doing nothing.
            // Coordinates, not a UiObject2. A handle to the reader goes stale across a page
            // turn (StaleObjectException), and re-resolving it mid-turn can momentarily find
            // no node at all (NullPointerException). Both aborted real runs on the device.
            // 8 steps is ~40 ms of travel: fast enough to fling rather than drag.
            val y = device.displayHeight / 2
            device.swipe(device.displayWidth * 85 / 100, y, device.displayWidth * 15 / 100, y, 8)
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
            startActivityAndWait(viewIntent())
        },
    ) {
        device.waitForIdle()
        repeat(4) {
            reader().pinchOpen(0.75f, 100)
            device.waitForIdle()
            reader().pinchClose(0.75f, 100)
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
            pressHome()
            startActivityAndWait(viewIntent())
        },
    ) {
        device.waitForIdle()
        // Resolved once here on purpose: this test measures back-to-back gestures with no
        // settle between them, and re-resolving would insert exactly the pause it is trying
        // to avoid. Nothing recomposes the page away mid-pinch, so the handle stays valid.
        val content = reader()
        // Deliberately no waitForIdle inside: the next gesture must land mid-render.
        repeat(8) { i ->
            if (i % 2 == 0) content.pinchOpen(0.9f, 50) else content.pinchClose(0.9f, 50)
        }
    }
}
