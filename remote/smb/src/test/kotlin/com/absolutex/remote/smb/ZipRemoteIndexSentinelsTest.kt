package com.absolutex.remote.smb

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The ZIP64 size and offset sentinels are flavors ranged reads cannot serve, like the count. */
class ZipRemoteIndexSentinelsTest {

    @Test fun `zip64 size sentinel degrades to IOException`() {
        assertEocdRejected(patchField = EOCD_CD_SIZE)
    }

    @Test fun `zip64 offset sentinel degrades to IOException`() {
        assertEocdRejected(patchField = EOCD_CD_OFFSET)
    }

    private fun assertEocdRejected(patchField: Int) {
        val archive = ZipFixtures.cbz("page01.jpg" to ZipFixtures.pageBytes(1))
        val patched = archive.copyOf()
        le32(patched, patched.size - 22 + patchField, 0xFFFFFFFFL)
        val reader = SeekableSmbReader(
            FakeSmbTransport(mapOf("b.cbz" to patched)),
            "b.cbz",
            patched.size.toLong(),
        )
        try {
            ZipRemoteIndex.open(reader)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("end-of-central-directory") == true)
        }
    }

    companion object {
        private const val EOCD_CD_SIZE = 12
        private const val EOCD_CD_OFFSET = 16

        private fun le32(bytes: ByteArray, at: Int, v: Long) {
            for (i in 0 until 4) {
                bytes[at + i] = (v shr (8 * i)).toByte()
            }
        }
    }
}
