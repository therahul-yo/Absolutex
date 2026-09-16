package com.absolutex.remote.ftp

import java.nio.file.Files
import org.apache.commons.net.ftp.FTPClient
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Runs [CommonsNetFtpTransport] against a real, in-process FTP server implementation
 * (org.apache.ftpserver — the same library commons-net's own test suite uses to test its FTP
 * client, per its POM) instead of [CommonsNetFtpTransportTest]'s fake `FTPClient`.
 *
 * That fake overrides `completePendingCommand()` to always return true, so it cannot catch what a
 * real server does: closing the data stream early (our range ends before EOF) gets answered with
 * 426, not a positive completion — confirmed here by spiking this exact server against
 * commons-net 3.13.0 before writing the fix (see the libs.versions.toml note on ftpserverCore).
 * Against the pre-fix `transfer()`, the first read below throws "FTP transfer did not complete"
 * and this test fails.
 */
class CommonsNetFtpTransportRealServerTest {

    private lateinit var server: FtpServer
    private var port: Int = 0
    private lateinit var payload: ByteArray

    @Before
    fun startServer() {
        val root = Files.createTempDirectory("ftp-real-server-test")
        payload = ByteArray(PAYLOAD_SIZE) { it.toByte() }
        Files.write(root.resolve(FILE_NAME), payload)

        val userManager = PropertiesUserManagerFactory().createUserManager()
        val user = BaseUser().apply {
            name = USERNAME
            password = PASSWORD
            homeDirectory = root.toString()
            authorities = listOf(WritePermission())
        }
        userManager.save(user)

        // Port 0: let the OS pick a free ephemeral port, then read back what it bound.
        val listener = ListenerFactory().apply { port = 0 }.createListener()
        val factory = FtpServerFactory().apply {
            addListener("default", listener)
            setUserManager(userManager)
        }
        server = factory.createServer().also { it.start() }
        port = factory.getListener("default")!!.port
    }

    @After
    fun stopServer() {
        server.stop()
    }

    /** Counts real connect() calls, so a test can prove the transport reused one connection. */
    private class CountingFtpClient : FTPClient() {
        var connects = 0

        override fun connect(hostname: String, port: Int) {
            connects++
            super.connect(hostname, port)
        }
    }

    @Test
    fun `readAt succeeds on a real server's 426 early-close reply and keeps the connection`() {
        val client = CountingFtpClient()
        val location = FtpLocation("127.0.0.1", port, USERNAME, "/$FILE_NAME", useTls = false)
        val transport = CommonsNetFtpTransport(location, { PASSWORD.toCharArray() }, { client })

        // The range ends well before EOF, so the server must close the data stream early and
        // reply 426 rather than a plain positive completion — exactly the case the fix covers.
        val first = transport.readAt("/$FILE_NAME", RANGE_OFFSET, RANGE_LENGTH)
        assertArrayEquals(payload.copyOfRange(RANGE_OFFSET.toInt(), (RANGE_OFFSET + RANGE_LENGTH).toInt()), first)

        // A second read on the same transport must not need a reconnect: the 426 was not a real
        // failure, so the control connection should have been left alone.
        val secondOffset = RANGE_OFFSET + RANGE_LENGTH
        val second = transport.readAt("/$FILE_NAME", secondOffset, RANGE_LENGTH)
        assertArrayEquals(payload.copyOfRange(secondOffset.toInt(), (secondOffset + RANGE_LENGTH).toInt()), second)
        assertEquals(1, client.connects)
    }

    private companion object {
        const val USERNAME = "reader"
        const val PASSWORD = "pw"
        const val FILE_NAME = "book.cbz"
        const val PAYLOAD_SIZE = 3 * 1024 * 1024
        const val RANGE_OFFSET = 1_000_000L
        const val RANGE_LENGTH = 65_536
    }
}
