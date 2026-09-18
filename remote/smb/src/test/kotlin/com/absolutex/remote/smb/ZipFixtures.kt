package com.absolutex.remote.smb

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds fixture archives in memory — no corpus files, no network, no device. */
object ZipFixtures {

    fun cbz(
        vararg pages: Pair<String, ByteArray>,
        stored: Boolean = false,
        storedNames: Set<String> = emptySet(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in pages) {
                val entry = ZipEntry(name)
                // STORED entries keep their full size: patterned bytes would otherwise DEFLATE
                // to nothing and "no full download" would be unmeasurable.
                if (stored || name in storedNames) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    val crc = CRC32()
                    crc.update(bytes)
                    entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Minimal bytes that still decode distinctly per page. Content never decoded in these tests. */
    fun pageBytes(seed: Int, size: Int = 1024): ByteArray =
        ByteArray(size) { ((seed * 31 + it) % 251).toByte() }

    /**
     * A large non-page entry inside the archive, so "no full download" is measurable.
     * Incompressible bytes: DEFLATE cannot shrink the range away and hide the traffic.
     */
    fun paddingEntry(name: String = "preview.dat", padBytes: Int = 256 * 1024): Pair<String, ByteArray> =
        name to ByteArray(padBytes) { (it % 251).toByte() }
}
