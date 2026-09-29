package com.absolutex.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStatusTest {

    private val a = "content://tree/a"
    private val b = "content://tree/b"

    @Test fun `starts idle`() {
        assertEquals(ScanSnapshot(), ScanStatus().state.value)
    }

    @Test fun `a started location is scanning until it finishes`() {
        val status = ScanStatus()
        status.started(listOf(a, b))
        assertEquals(setOf(a, b), status.state.value.scanning)
        status.finished(a, unreadable = false)
        assertEquals(setOf(b), status.state.value.scanning)
    }

    @Test fun `an unreadable finish is remembered and a clean one clears it`() {
        val status = ScanStatus()
        status.started(listOf(a))
        status.finished(a, unreadable = true)
        assertEquals(setOf(a), status.state.value.unreadable)

        status.started(listOf(a))
        assertTrue("a rescan forgets the old verdict while it runs", status.state.value.unreadable.isEmpty())
        status.finished(a, unreadable = false)
        assertTrue(status.state.value.unreadable.isEmpty())
    }

    @Test fun `abandoning stops the scanning without judging the folder`() {
        val status = ScanStatus()
        status.started(listOf(a))
        status.abandoned(listOf(a))
        assertEquals(ScanSnapshot(), status.state.value)
    }

    @Test fun `abandoning after finishing keeps the verdict`() {
        val status = ScanStatus()
        status.started(listOf(a))
        status.finished(a, unreadable = true)
        status.abandoned(listOf(a))
        assertEquals(setOf(a), status.state.value.unreadable)
        assertTrue(status.state.value.scanning.isEmpty())
    }

    @Test fun `read failures count only what is recorded`() {
        val failures = ReadFailures()
        assertFalse(failures.any)
        failures.record()
        assertTrue(failures.any)
    }
}
