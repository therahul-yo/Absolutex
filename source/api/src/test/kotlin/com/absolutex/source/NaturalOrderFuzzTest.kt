package com.absolutex.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Phase 4: property checks over [NaturalOrder] (all JVM, no device needed).
 *
 * The hand-written cases in [NaturalOrderTest] pin known orderings; these fuzz checks pin the
 * comparator LAWS (antisymmetry, transitivity, sort-consistency) over generated names so a
 * future tweak cannot introduce a cycle that would corrupt page order or crash sort().
 */
class NaturalOrderFuzzTest {

    // Fixed seed: a failure must reproduce exactly, not flake once a month.
    private val random = Random(0xAB50_1173L)

    private fun genName(): String {
        val parts = listOf("page", "ch", "Absolute Batman 001 (2024) ", "ZZZZZ", "img", "10x", "_")
        val sb = StringBuilder()
        repeat(random.nextInt(1, 4)) {
            when (random.nextInt(5)) {
                0 -> sb.append(parts.random(random))
                1 -> sb.append(random.nextInt(0, 200))
                2 -> sb.append("0".repeat(random.nextInt(0, 4)) + random.nextInt(0, 200))
                3 -> sb.append(('a'..'z').random(random))
                else -> sb.append(random.nextBoolean().let { if (it) "/" else "\\" })
            }
        }
        return sb.append(listOf(".jpg", ".png", ".jxl", ".jp2").random(random)).toString()
    }

    @Test fun `comparator is transitive over random triples`() {
        val names = List(300) { genName() }
        repeat(2000) {
            val (a, b, c) = Triple(names.random(random), names.random(random), names.random(random))
            val ab = NaturalOrder.compare(a, b).coerceIn(-1, 1)
            val bc = NaturalOrder.compare(b, c).coerceIn(-1, 1)
            val ac = NaturalOrder.compare(a, c).coerceIn(-1, 1)
            if (ab == 0 && bc == 0) assertEquals("equality not transitive: $a / $b / $c", 0, ac)
            if (ab <= 0 && bc <= 0) assertTrue("order cycle: $a / $b / $c", ac <= 0)
            if (ab >= 0 && bc >= 0) assertTrue("order cycle: $a / $b / $c", ac >= 0)
        }
    }

    @Test fun `sorted output is pairwise ordered`() {
        repeat(50) {
            val names = List(60) { genName() }
            val sorted = names.sortedWith(NaturalOrder)
            for (i in 0 until sorted.size - 1) {
                assertTrue(
                    "out of order: '${sorted[i]}' vs '${sorted[i + 1]}'",
                    NaturalOrder.compare(sorted[i], sorted[i + 1]) <= 0,
                )
            }
        }
    }

    @Test fun `digit versus nondigit is deterministic both ways`() {
        val pairs = listOf("10.jpg" to "a.jpg", "2" to "/", "x9" to "x/")
        for ((a, b) in pairs) {
            val f = NaturalOrder.compare(a, b)
            val r = NaturalOrder.compare(b, a)
            assertEquals("asymmetric for '$a' vs '$b'", 0, f + r)
            // Total: never "equal" unless identical strings.
            if (a != b) assertTrue("inconsistent tie for '$a' vs '$b'", f != 0)
        }
    }
}
