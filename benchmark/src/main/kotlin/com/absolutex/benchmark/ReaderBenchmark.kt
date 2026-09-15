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

    private companion object {
        /** Each direction, per iteration. Well inside a 45-page book in either direction. */
        const val PAGE_TURNS = 5
    }


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

    /**
     * Refuses to measure anything but a loaded book. Runs in setupBlock, so none of it is timed.
     *
     * Every one of these guards exists because its absence produced a plausible, wrong number:
     * the picker showing, a notification shade covering the reader, and — for every run until it
     * was caught — the reader's own "Couldn't open this book" screen, which is the same package,
     * passes the other two checks, and reported one frame per iteration.
     *
     * The load is asynchronous, so the error screen is waited for rather than looked for once: an
     * immediate check passes while the page is still loading and the failure lands afterwards.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.assertReaderShowsABook() {
        check(device.wait(Until.gone(By.text("Open a comic")), 5_000) != false) {
            "app showed the picker - the book path was not honoured"
        }
        check(device.currentPackageName == pkg) {
            "reader is not in the foreground (found ${device.currentPackageName}) - nothing to measure"
        }
        // A real page loads in ~20 ms on the reference device; 3 s is generous.
        check(!device.wait(Until.hasObject(By.text("Retry")), 3_000)) {
            "the reader is showing its error screen - no book loaded, nothing to measure"
        }
        assertGesturesReachTheApp()
    }

    /**
     * Checked before EVERY injected gesture, not once per iteration. The reference device is a
     * personal phone: a call, a system dialog or the notification shade can take focus mid-run,
     * and a gesture landing there once changed the default dialer and placed a real call.
     * Stopping the run is always cheaper than whatever the gesture would have touched.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.guardFocus() {
        check(device.currentPackageName == pkg) {
            "focus left the reader (found ${device.currentPackageName}) - stopping before injecting a gesture"
        }
    }

    /**
     * OxygenOS/ColorOS silently drop injected input unless "Disable permission monitoring" is on
     * in Developer options. The swipes then do nothing, the reader draws no frames, and the run
     * dies at the end with "0 found for frameDurationCpuMs" — which reads like a tracing fault.
     * One nudge and a frame count says what is actually wrong, before any iteration is spent.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.assertGesturesReachTheApp() {
        device.executeShellCommand("dumpsys gfxinfo $pkg reset")
        val y = device.displayHeight / 2
        guardFocus()
        device.swipe(device.displayWidth * 85 / 100, y, device.displayWidth * 15 / 100, y, 8)
        device.waitForIdle()
        guardFocus()
        device.swipe(device.displayWidth * 15 / 100, y, device.displayWidth * 85 / 100, y, 8)
        device.waitForIdle()
        val frames = Regex("""Total frames rendered: (\d+)""")
            .find(device.executeShellCommand("dumpsys gfxinfo $pkg"))?.groupValues?.get(1)?.toInt() ?: 0
        check(frames > 0) {
            "a swipe drew no frames - injected input is being dropped. On OnePlus/OPPO enable " +
                "Developer options > Disable permission monitoring, then rerun"
        }
    }

    @Test fun pageTurn() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait(viewIntent())
            // A personal phone gets notifications. With the shade down the reader is covered,
            // the gestures land on the shade, and Perfetto reports "no renderthread slices" —
            // which is exactly how one run on the reference device failed.
            device.executeShellCommand("cmd statusbar collapse")
            assertReaderShowsABook()
        },
    ) {
        // Forward five, back five: net zero. Ten forward per iteration over five iterations is 50
        // turns, and the reference book has 45 pages — later iterations would pin at the last page,
        // turn nothing, and report a flawless score for an idle screen.
        val y = device.displayHeight / 2
        val right = device.displayWidth * 85 / 100
        val left = device.displayWidth * 15 / 100
        repeat(PAGE_TURNS) {
            // 8 steps is ~40 ms of travel: fast enough to register as a fling rather than a drag.
            guardFocus()
            device.swipe(right, y, left, y, 8)
            device.waitForIdle()
        }
        repeat(PAGE_TURNS) {
            guardFocus()
            device.swipe(left, y, right, y, 8)
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
            // A personal phone gets notifications. With the shade down the reader is covered,
            // the gestures land on the shade, and Perfetto reports "no renderthread slices" —
            // which is exactly how one run on the reference device failed.
            device.executeShellCommand("cmd statusbar collapse")
            assertReaderShowsABook()
        },
    ) {
        device.waitForIdle()
        repeat(4) {
            guardFocus()
            reader().pinchOpen(0.75f, 100)
            device.waitForIdle()
            guardFocus()
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
        // "Picker is gone" passes vacuously when something else covers the app, so assert the
        // reader is actually the foreground package before measuring anything. Without this the
        // run measured a notification shade and failed with a misleading Perfetto error.
        check(device.currentPackageName == pkg) {
            "reader is not in the foreground (found ${device.currentPackageName}) - nothing to measure"
        }
        // The reader's failure state is the same package and would pass every check above. It
        // has only a label and a Retry button, so Retry being present means no page loaded. This
        // is not paranoia: the corpus directory was unreadable (EACCES), and every run until then
        // timed "Couldn't open this book" and reported one frame per iteration as a result.
        check(device.findObject(By.text("Retry")) == null) {
            "the reader is showing its error screen - no book loaded, nothing to measure"
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
            // A personal phone gets notifications. With the shade down the reader is covered,
            // the gestures land on the shade, and Perfetto reports "no renderthread slices" —
            // which is exactly how one run on the reference device failed.
            device.executeShellCommand("cmd statusbar collapse")
            assertReaderShowsABook()
        },
    ) {
        device.waitForIdle()
        // Resolved once here on purpose: this test measures back-to-back gestures with no
        // settle between them, and re-resolving would insert exactly the pause it is trying
        // to avoid. Nothing recomposes the page away mid-pinch, so the handle stays valid.
        val content = reader()
        // Deliberately no waitForIdle inside: the next gesture must land mid-render. The focus check
        // is a single UiAutomation call, milliseconds — a pause worth paying on a personal phone.
        repeat(8) { i ->
            guardFocus()
            if (i % 2 == 0) content.pinchOpen(0.9f, 50) else content.pinchClose(0.9f, 50)
        }
    }
}
