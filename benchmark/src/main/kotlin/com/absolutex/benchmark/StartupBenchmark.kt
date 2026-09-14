package com.absolutex.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start timing for the app's launcher activity.
 *
 * Budget (§3): **cold start to interactive < 300 ms at P90**, measured on the reference
 * device (OnePlus 11R, SM8475, Android 16). The budget applies to [startupBaselineProfile]
 * — [CompilationMode.Partial] is what ships, because §3 makes baseline profiles mandatory.
 * The other two runs bracket it: [startupNoCompilation] is the worst case a user sees on
 * first launch before the profile is applied, and [startupFullCompilation] is the floor
 * that AOT can reach, so a Partial number close to Full means the profile is doing its job.
 *
 * Macrobenchmark has no assertion API, so nothing here fails on a regression. The gate is
 * host-side: `tools/check-startup-budget.py` parses the emitted JSON and enforces the 300 ms
 * P90. Run it after the benchmark.
 *
 * This measures `timeToInitialDisplayMs` — first frame. "Interactive" really means
 * `timeToFullDisplayMs`, which only exists once the app calls `reportFullyDrawn()`.
 * TODO(reader): have the reader call reportFullyDrawn() when the first page is on screen,
 * then re-point the budget at TTFD — the checker already prefers it when present.
 *
 * Requires a physical device; an emulator's numbers are not comparable. Run with:
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

        /**
         * Cold start on this hardware has a long tail driven by the scheduler parking work on
         * the little cores. 20 iterations is enough for a P90 that does not swing by more than
         * a few ms between runs; 10 is not. Each iteration costs roughly a second.
         */
        const val ITERATIONS = 20
    }
}
