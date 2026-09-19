package com.absolutex.remote.ftp

import java.io.IOException
import java.io.InputStream
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPSClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CommonsNetFtpTransportTest {

    private class TricklingStream(data: ByteArray, private val chunk: Int) : InputStream() {
        private val bytes: ByteArray = data
        private var pos = 0

        override fun read(): Int = if (pos >= bytes.size) -1 else bytes[pos++].toInt() and 0xFF

        override fun read(target: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val count = minOf(chunk, len, bytes.size - pos)
            System.arraycopy(bytes, pos, target, off, count)
            pos += count
            return count
        }
    }

    private open class FakeFtpClient(val bytes: ByteArray, private val chunk: Int) : FTPClient() {
        var restartSeen = -1L
        var retrieveCalls = 0
        var completions = 0
        var connects = 0
        var refuseRetr = false

        override fun connect(host: String, port: Int) {
            connects++
        }

        // No-ops: the real ones dereference the control socket, which never exists here.
        override fun setConnectTimeout(timeout: Int) = Unit

        override fun setSoTimeout(timeout: Int) = Unit

        override fun login(username: String, password: String): Boolean = true

        override fun logout(): Boolean = true

        override fun disconnect() = Unit

        override fun isConnected(): Boolean = true

        override fun setFileType(fileType: Int): Boolean = true

        override fun setRestartOffset(offset: Long) {
            restartSeen = offset
        }

        override fun retrieveFileStream(path: String): InputStream? {
            retrieveCalls++
            if (refuseRetr) return null
            val from = restartSeen.coerceAtLeast(0).toInt()
            return TricklingStream(bytes.copyOfRange(from, bytes.size), chunk)
        }

        override fun completePendingCommand(): Boolean {
            completions++
            return true
        }

        override fun mlistFile(path: String): FTPFile {
            val file = FTPFile()
            file.name = path
            file.setSize(bytes.size.toLong())
            return file
        }
    }

    private class FakeFtpsClient(bytes: ByteArray, chunk: Int) : FTPSClient() {
        private val backing = FakeFtpClient(bytes, chunk)
        var pbszSeen = -1L
        var protSeen: String? = null

        override fun connect(host: String, port: Int) = backing.connect(host, port)

        override fun setConnectTimeout(timeout: Int) = backing.setConnectTimeout(timeout)

        override fun setSoTimeout(timeout: Int) = backing.setSoTimeout(timeout)

        override fun login(username: String, password: String): Boolean = backing.login(username, password)

        override fun logout(): Boolean = backing.logout()

        override fun disconnect() = backing.disconnect()

        override fun isConnected(): Boolean = backing.isConnected

        override fun setFileType(fileType: Int): Boolean = backing.setFileType(fileType)

        override fun setRestartOffset(offset: Long) = backing.setRestartOffset(offset)

        override fun retrieveFileStream(path: String): InputStream? = backing.retrieveFileStream(path)

        override fun completePendingCommand(): Boolean = backing.completePendingCommand()

        override fun mlistFile(path: String): FTPFile = backing.mlistFile(path)

        override fun execPBSZ(pbsz: Long) {
            pbszSeen = pbsz
        }

        override fun execPROT(prot: String) {
            protSeen = prot
        }
    }

    private val payload = ByteArray(4096) { it.toByte() }
    private val location = FtpLocation("h", 21, "u", "/b.cbz", false)

    private fun transport(client: FakeFtpClient): CommonsNetFtpTransport =
        CommonsNetFtpTransport(location, { "pw".toCharArray() }, { client })

    @Test fun `readAt returns the exact slice through single-byte socket reads`() {
        val client = FakeFtpClient(payload, 1)
        val got = transport(client).readAt("/b.cbz", 100, 500)
        assertArrayEquals(payload.copyOfRange(100, 600), got)
    }

    @Test fun `readAt sets the restart offset and completes the transfer`() {
        val client = FakeFtpClient(payload, 512)
        transport(client).readAt("/b.cbz", 1000, 100)
        assertEquals(1000L, client.restartSeen)
        assertEquals(1, client.completions)
        assertEquals(1, client.retrieveCalls)
    }

    @Test fun `short server read degrades to IOException`() {
        val client = FakeFtpClient(payload, 64)
        var thrown: IOException? = null
        try {
            transport(client).readAt("/b.cbz", 4000, 500)
        } catch (expected: IOException) {
            thrown = expected
        }
        assertNotNull(thrown)
    }

    @Test fun `refused RETR degrades to IOException`() {
        val client = FakeFtpClient(payload, 64).apply { refuseRetr = true }
        var thrown: IOException? = null
        try {
            transport(client).readAt("/b.cbz", 0, 10)
        } catch (expected: IOException) {
            thrown = expected
        }
        assertNotNull(thrown)
    }

    @Test fun `failed transfer reconnects on the next call`() {
        val client = FakeFtpClient(payload, 64)
        val live = transport(client)
        try {
            live.readAt("/b.cbz", 4000, 500)
        } catch (expected: IOException) {
            assertNotNull(expected)
        }
        val got = live.readAt("/b.cbz", 0, 10)
        assertArrayEquals(payload.copyOfRange(0, 10), got)
        // Bounded retry first: the short read is transient, so the failing call burns
        // its three-attempt budget (three connects) before the next call reconnects.
        assertEquals(4, client.connects)
    }

    @Test fun `sizeBytes reports the listed size`() {
        assertEquals(payload.size.toLong(), transport(FakeFtpClient(payload, 64)).sizeBytes("/b.cbz"))
    }

    @Test fun `zero-length read returns empty without a RETR`() {
        val client = FakeFtpClient(payload, 64)
        assertEquals(0, transport(client).readAt("/b.cbz", 10, 0).size)
        assertEquals(0, client.retrieveCalls)
    }

    @Test fun `password copy is zeroed after login`() {
        var handed: CharArray? = null
        val live = CommonsNetFtpTransport(location, { "pw".toCharArray().also { handed = it } }) {
            FakeFtpClient(payload, 64)
        }
        live.readAt("/b.cbz", 0, 4)
        assertArrayEquals(CharArray(2), requireNotNull(handed))
    }

    @Test fun `ftps selects explicit TLS with a private data channel`() {
        val ftps = FakeFtpsClient(payload, 64)
        val ftpsLocation = FtpLocation("h", 990, "u", "/b.cbz", true)
        val live = CommonsNetFtpTransport(ftpsLocation, { "pw".toCharArray() }, { ftps })
        val got = live.readAt("/b.cbz", 0, 8)
        assertArrayEquals(payload.copyOfRange(0, 8), got)
        assertEquals(0L, ftps.pbszSeen)
        assertEquals("P", ftps.protSeen)
    }
}
