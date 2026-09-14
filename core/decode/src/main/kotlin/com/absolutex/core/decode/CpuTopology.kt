package com.absolutex.core.decode

import java.io.File

/**
 * Counts the big/prime cores, not every core.
 *
 * §3 requires CPU-bound decode on a dispatcher sized to the big cores. `availableProcessors()`
 * reports 8 on the reference device (SD 8+ Gen 1 = 1x X2 + 3x A710 + 4x A510), and saturating
 * all 8 pushes work onto the little cores where a 2400x3600 JPEG decode runs several times
 * slower while still competing for memory bandwidth — it lengthens the critical path instead of
 * shortening it.
 *
 * Max frequency per core is read from sysfs and cores are grouped by cluster; everything at or
 * above the median distinct frequency counts as "big". Sysfs is unreadable on some OEM builds,
 * so the fallback is half the core count, which is right for every big.LITTLE layout we target.
 */
object CpuTopology {

    val bigCoreCount: Int by lazy { detect() }

    /** ponytail: sysfs read + median split. Override via [detectFrom] if a device measures wrong. */
    private fun detect(): Int {
        val total = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until total).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLongOrNull()
            }.getOrNull()
        }
        return detectFrom(freqs, total)
    }

    internal fun detectFrom(freqs: List<Long>, totalCores: Int): Int {
        val fallback = (totalCores / 2).coerceAtLeast(1)
        if (freqs.size != totalCores || freqs.isEmpty()) return fallback

        val distinct = freqs.distinct().sorted()
        if (distinct.size == 1) return totalCores          // homogeneous: every core is a big core

        // Median of the distinct cluster frequencies; cores at or above it are big.
        val threshold = distinct[distinct.size / 2]
        return freqs.count { it >= threshold }.coerceAtLeast(1)
    }
}
