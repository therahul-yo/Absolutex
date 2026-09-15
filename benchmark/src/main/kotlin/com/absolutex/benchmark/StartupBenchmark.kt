package com.absolutex.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Cold-start timing for the app's launcher activity.
 *
 * Budget (§3): **cold start to interactive < 300 ms at P90** on the reference device
 * (OnePlus 11R, SM8475, Android 16). The budget applies to [startupBaselineProfile] —
 * [CompilationMode.Partial] is the shipping configuration, because §3 makes baseline
 * profiles mandatory. [startupNoCompilation] and [startupFullCompilation] bracket it: a
 * Partial number close to Full means the profile is doing its job.
 *
 * The `benchmark` build type inherits release's R8 and resource shrinking, so the APK measured
 * here is the one that ships.
 *
 * **What "cold start" reaches depends on persisted state.** MainActivity resumes the last
 * book (§5.2) via a DataStore read plus a persisted-Uri check, and renders nothing until
 * that resolves. So:
 *  - after a clean install (what Gradle's `connectedAndroidTest` forces, since it
 *    uninstalls between runs) there is no saved book and startup lands on the picker —
 *    this is the first-run path;
 *  - with the app left installed and a book remembered, startup lands in the reader —
 *    the returning-user path, and the slower of the two.
 * They are different numbers. Say which one you are quoting.
 *
 * Macrobenchmark has no assertion API, so nothing here fails on a regression. The gate is
 * host-side: `tools/check-startup-budget.py` parses the emitted JSON and enforces the P90.
 *
 * `timeToInitialDisplayMs` is the first frame. `timeToFullDisplayMs` is the first page's pixels:
 * the reader reports fully drawn when the resumed page's base layer is decoded. On a launch that
 * lands on the picker there is no page, so no fully-drawn report and no TTFD.
 *
 * Known flake: Macrobenchmark occasionally fails an iteration with "No Choreographer#doFrame
 * (or RT frame slice) ends after reportFullyDrawn" when the report lands on the last frame before
 * the app goes idle. It has hit the None and Full compilation modes, never the Partial one the
 * budget uses. Re-run before treating it as a regression.
 *
 * Requires a physical device; an emulator's numbers are not comparable. Prefer the project's
 * runner, which keeps the app installed (the returning-user path):
 *
 *     tools/run-benchmark.sh com.absolutex.benchmark.StartupBenchmark
 *     python3 tools/check-startup-budget.py --budget-ms 300 --results <pulled-json-dir>
 *
 * Or via Gradle, which reinstalls and so measures the first-run path:
 *
 *     ./gradlew :benchmark:connectedBenchmarkAndroidTest
 *     python3 tools/check-startup-budget.py --budget-ms 300
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** First launch after install: no profile, everything interpreted or JIT'd. Upper bound. */
    @Test
    fun startupNoCompilation() = measureStartup(CompilationMode.None())

    /** What ships. This is the run the §3 budget applies to. */
    @Test
    fun startupBaselineProfile() = measureStartup(CompilationMode.Partial())

    /** Fully AOT-compiled. Not shippable; it is here as the floor to compare Partial against. */
    @Test
    fun startupFullCompilation() = measureStartup(CompilationMode.Full())

    /**
      * §3's "tap book -> first page rendered < 250 ms". That budget is a WARM tap from the library,
      * not a cold start: the process is already up, and what the user waits for is the archive open
      * plus one page decode. timeToFullDisplayMs is the number, and it exists because the reader
      * reports fully drawn when the page's base layer is decoded.
      *
      * The book is opened by path through ACTION_VIEW, the same way the reader benchmark does, so
      * this measures the reader rather than SAF.
      */
     @Test
     fun tapToFirstPage() = benchmarkRule.measureRepeated(
         packageName = TARGET_PACKAGE,
         metrics = listOf(StartupTimingMetric()),
         compilationMode = CompilationMode.Partial(),
         startupMode = StartupMode.WARM,
         iterations = ITERATIONS,
         setupBlock = { pressHome() },
     ) {
         startActivityAndWait(
             Intent(Intent.ACTION_VIEW).apply {
                 setClassName(TARGET_PACKAGE, "com.absolutex.MainActivity")
                 data = Uri.fromFile(File(BOOK))
                 addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
             },
         )
     }

    private fun measureStartup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        // COLD kills the process between iterations, so each measurement includes process
        // fork, class loading and Application.onCreate — the part a user actually waits for.
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            // Back to the launcher first. Without this the activity may already be resumed
            // and the "cold" start measures a resume instead.
            pressHome()
        },
    ) {
        startActivityAndWait()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.absolutex"

        /** Staged by tools/run-benchmark.sh; the reader benchmark opens the same file. */
        const val BOOK = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"

        /**
         * Cold start on this hardware has a long tail driven by the scheduler parking work on
         * the little cores. 20 iterations is enough for a P90 that does not swing by more than
         * a few ms between runs; 10 is not. Each iteration costs roughly a second.
         */
        const val ITERATIONS = 20
    }
}
