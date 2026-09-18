package com.absolutex.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M7 instrumentation-only baseline, branched from main without the unmerged GPU stack.
 * Mirrors StartupBenchmark.tapToFirstPage: same corpus, warm launch, Partial, 20 iterations.
 * Run: tools/run-benchmark.sh com.absolutex.benchmark.StartupBreakdownBenchmark#tapToFirstPage
 *
 * Report timeToFullDisplayMs median (budget <250 ms), not a sum of trace durations.
 * archiveOpen INCLUDES entryList (native listing plus FD open); headerParse includes region
 * decoder construction. FIRST selects the first invocation in the measurement window, not
 * necessarily archive ordinal zero: persisted progress can resume another page. Keep book,
 * saved page, fit/layout, profile and device temperature identical for before/after runs.
 *
 * firstDraw times CPU canvas command recording, NOT GPU completion or display presentation.
 * ReportDrawnWhen is unchanged: the existing base-ready callback also fires on decode failure.
 * Reject error/loading-screen runs and confirm successful baseDecode and firstDraw slices in
 * Perfetto. Scheduling, composition, progress reads and RenderThread account for gaps; slices
 * overlap, and their medians must not be added or called exclusive stage costs.
 *
 * No phone result is available from compilation/JVM tests. The 329.8 ms median in README is
 * historical context, not a measurement of this branch. Collect this baseline before changing
 * decode strategy, then repeat this exact method for the candidate.
 */
@OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class StartupBreakdownBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun tapToFirstPage() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            TraceSectionMetric("absx.archiveOpen", TraceSectionMetric.Mode.First),
            TraceSectionMetric("absx.entryList", TraceSectionMetric.Mode.First),
            TraceSectionMetric("absx.entryExtract", TraceSectionMetric.Mode.First),
            TraceSectionMetric("absx.headerParse", TraceSectionMetric.Mode.First),
            TraceSectionMetric("absx.baseDecode", TraceSectionMetric.Mode.First),
            TraceSectionMetric("absx.firstDraw", TraceSectionMetric.Mode.First),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 20,
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

    private companion object {
        const val TARGET_PACKAGE = "com.absolutex"
        const val BOOK = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"
    }
}
