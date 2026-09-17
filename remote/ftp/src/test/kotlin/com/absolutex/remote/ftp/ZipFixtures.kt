package com.absolutex.remote.ftp

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds real ZIP bytes on the JVM so archive tests never touch the network. */
internal object ZipFixtures {

    fun pageBytes(seed: Byte, size: Int): ByteArray = ByteArray(size) { (seed + it).toByte() }

    fun build(entries: List<Triple<String, Int, ByteArray>>): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            for ((name, method, data) in entries) {
                val entry = ZipEntry(name)
                if (method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().also { it.update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return sink.toByteArray()
    }
}
