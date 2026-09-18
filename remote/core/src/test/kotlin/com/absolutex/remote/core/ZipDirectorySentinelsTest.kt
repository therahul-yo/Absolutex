package com.absolutex.remote.core

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The ZIP64 size and offset sentinels are flavors ranged reads cannot serve, like the count. */
class ZipDirectorySentinelsTest {

    @Test fun `zip64 size sentinel degrades to IOException`() {
        assertEocdSentinelRejected(fieldOffset = EOCD_SIZE_OFF)
    }

    @Test fun `zip64 offset sentinel degrades to IOException`() {
        assertEocdSentinelRejected(fieldOffset = EOCD_OFFSET_OFF)
    }

    private fun assertEocdSentinelRejected(fieldOffset: Int) {
        val archive = ZipBytes.cbz("page01.jpg" to ZipBytes.pageBytes(1))
        val patched = archive.copyOf()
        ZipBytes.le32(patched, ZipBytes.eocdStart(patched) + fieldOffset, 0xFFFFFFFFL)
        try {
            ZipDirectory.open(FakeRangeTransport(patched)::readAt, patched.size.toLong())
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("end-of-central-directory") == true)
        }
    }

    companion object {
        private const val EOCD_SIZE_OFF = 12
        private const val EOCD_OFFSET_OFF = 16
    }
}
