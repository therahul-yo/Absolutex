package com.absolutex.core.decode

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTopologyTest {

    // Snapdragon 8+ Gen 1 (SM8475). These are the exact cpuinfo_max_freq values read off the
    // reference OnePlus 11R over adb, not spec-sheet numbers: 4x A510, 3x A710, 1x X2.
    @Test fun `sd8plusgen1 reports four big cores`() {
        val freqs = listOf(1_804_800L, 1_804_800L, 1_804_800L, 1_804_800L,
                           2_496_000L, 2_496_000L, 2_496_000L, 2_995_200L)
        assertEquals(4, CpuTopology.detectFrom(freqs, 8))
    }

    @Test fun `homogeneous cpu uses every core`() {
        assertEquals(4, CpuTopology.detectFrom(List(4) { 2_000_000L }, 4))
    }

    @Test fun `unreadable sysfs falls back to half the cores`() {
        assertEquals(4, CpuTopology.detectFrom(emptyList(), 8))
    }

    @Test fun `partial sysfs read falls back rather than undercounting`() {
        assertEquals(4, CpuTopology.detectFrom(listOf(1_800_000L, 2_400_000L), 8))
    }

    @Test fun `never returns zero`() {
        assertEquals(1, CpuTopology.detectFrom(emptyList(), 1))
    }
}
