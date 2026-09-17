package com.absolutex.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Writes the baseline profile the release build ships (§3 cold start).
 *
 * What it walks is what a reader does in the first seconds: the launcher opening the library, and
 * a book opened straight from a file manager, which is the path §3 measures from the tap. Anything
 * not walked here is JIT-compiled on a user's first run.
 *
 * Run with `tools/run-benchmark.sh com.absolutex.benchmark.BaselineProfileGenerator`. The profile
 * lands on the device under `Android/media/com.absolutex.benchmark/`.
 *
 * NOT yet usable as `app/src/main/baseline-prof.txt`: the benchmark build type inherits release's
 * R8, so the names collected here are already obfuscated, while a committed profile must name
 * classes as the source does and be mapped by R8 at build time. Generating against a non-minified
 * variant is what the androidx baseline-profile plugin does; adopting it needs the plugin's own
 * variants reconciled with this module's custom `benchmark` build type, which is the next step.
 */
class BaselineProfileGenerator {

    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE) {
        // The library as home: the launcher path every other start begins from.
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // A book from a file manager: the reader, its first decode, and one page turn.
        pressHome()
        startActivityAndWait(
            Intent(Intent.ACTION_VIEW).apply {
                setClassName(PACKAGE, "com.absolutex.MainActivity")
                data = Uri.fromFile(File(BOOK))
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), READY_MS)
        device.waitForIdle()
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width * 4 / 5, height / 2, width / 5, height / 2, SWIPE_STEPS)
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "com.absolutex"

        /** Staged by tools/run-benchmark.sh, the same book every other benchmark opens. */
        const val BOOK = "/sdcard/Android/data/com.absolutex/files/absolute-batman-001.cbr"
        const val READY_MS = 5_000L
        const val SWIPE_STEPS = 10
    }
}
