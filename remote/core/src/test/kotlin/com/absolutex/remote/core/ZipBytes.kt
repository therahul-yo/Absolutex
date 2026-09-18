package com.absolutex.remote.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** In-memory fixture archives plus the little-endian patch helpers hostile tests need. */
object ZipBytes {

    fun cbz(vararg pages: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in pages) {
                val entry = ZipEntry(name)
                if (stored) {
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

    /** Minimal bytes that still compare distinctly per page. Content never decoded here. */
    fun pageBytes(seed: Int, size: Int = 1024): ByteArray =
        ByteArray(size) { ((seed * 31 + it) % 251).toByte() }

    fun le16(bytes: ByteArray, at: Int, v: Int) {
        bytes[at] = v.toByte()
        bytes[at + 1] = (v shr 8).toByte()
    }

    fun le32(bytes: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) {
            bytes[at + i] = (v shr (8 * i)).toByte()
        }
    }

    fun u32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toInt() and 0xFF).toLong() or
            ((bytes[at + 1].toInt() and 0xFF).toLong() shl 8) or
            ((bytes[at + 2].toInt() and 0xFF).toLong() shl 16) or
            ((bytes[at + 3].toInt() and 0xFF).toLong() shl 24)

    /** Start of the EOCD in a fixture with an empty comment. */
    fun eocdStart(bytes: ByteArray): Int = bytes.size - 22
}
