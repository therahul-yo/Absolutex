package com.absolutex.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The text book's entry cache stays within its byte budget and keeps what is used. */
class ByteLruTest {

    @Test fun `stays within its byte budget`() {
        val lru = ByteLru(budget = 100)
        repeat(50) { lru.put("chapter$it", ByteArray(30)) }
        assertEquals(90L, lru.bytes)
    }

    @Test fun `evicts the least recently used, keeping what was read again`() {
        val lru = ByteLru(budget = 100)
        lru.put("style.css", ByteArray(10))
        lru.put("c1", ByteArray(40))
        lru.put("c2", ByteArray(40))
        lru["style.css"]
        lru.put("c3", ByteArray(40))
        assertNotNull(lru["style.css"])
        assertNull(lru["c1"])
        assertNotNull(lru["c3"])
    }

    @Test fun `an entry larger than the budget is not kept`() {
        val lru = ByteLru(budget = 100)
        lru.put("huge", ByteArray(101))
        assertNull(lru["huge"])
        assertEquals(0L, lru.bytes)
    }

    @Test fun `replacing an entry does not count it twice`() {
        val lru = ByteLru(budget = 100)
        lru.put("c1", ByteArray(60))
        lru.put("c1", ByteArray(60))
        assertEquals(60L, lru.bytes)
    }
}
