package com.absolutex.source

import org.junit.Assert.assertEquals
import org.junit.Test

class PageReadabilityTest {
    @Test fun `counts extracted payloads not listed headers`() {
        val visited = mutableListOf<Int>()
        val report = PageReadability.inspect(listOf(8, 3, 7), declaredTotal = 20) {
            visited += it
            it != 7
        }
        assertEquals(listOf(8, 3, 7), visited)
        assertEquals(PageReadability(2, 20), report)
    }

    @Test fun `missing metadata never turns a partial listing into a total`() {
        assertEquals(PageReadability(2, null), PageReadability.inspect(listOf(1, 2), null) { true })
    }

    @Test fun `contradictory metadata cannot be used as the total`() {
        assertEquals(PageReadability(1, null), PageReadability.inspect(listOf(1, 2), 1) { it == 1 })
        assertEquals(PageReadability(0, null), PageReadability.inspect(emptyList(), 0) { true })
        assertEquals(PageReadability(0, 20), PageReadability.inspect(emptyList(), 20) { true })
    }
}
