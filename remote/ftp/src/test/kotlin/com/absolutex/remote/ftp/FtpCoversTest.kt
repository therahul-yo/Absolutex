package com.absolutex.remote.ftp

import java.nio.file.Files
import java.util.zip.ZipEntry
import org.apache.commons.net.ftp.FTPClient
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * [FtpCovers] against a real, in-process FTP server (the same trio
 * `CommonsNetFtpTransportRealServerTest` uses): the happy path proves a grid cover costs index
 * transfers plus the first entry over the wire, never a hand-rolled fixture of the source.
 * Hostile archives ride a real [FtpZipSource] over the in-memory transport instead — same
 * pattern as [FtpZipTest] — because what is hostile is the directory, not the socket.
 */
class FtpCoversTest {

    private lateinit var server: FtpServer
    private var port: Int = 0
    private lateinit var root: java.nio.file.Path

    private val first = ZipFixtures.pageBytes(7, 3000)
    private val second = ZipFixtures.pageBytes(9, 5000)

    @Before
    fun startServer() {
        root = Files.createTempDirectory("ftp-covers-test")
        val bytes = ZipFixtures.build(
            listOf(
                Triple("cover.jpg", ZipEntry.STORED, first),
                Triple("page02.jpg", ZipEntry.STORED, second),
                Triple("padding.bin", ZipEntry.STORED, ByteArray(PADDING_BYTES)),
            ),
        )
        Files.write(root.resolve(FILE_NAME), bytes)

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

    private fun liveTransport(): CommonsNetFtpTransport {
        val location = FtpLocation("127.0.0.1", port, USERNAME, "/", useTls = false)
        return CommonsNetFtpTransport(location, { PASSWORD.toCharArray() }, { FTPClient() })
    }

    @Test fun `cover over the real server equals the first page`() {
        val transport = liveTransport()
        try {
            val source = FtpZipSource.open(transport, "/$FILE_NAME")
            source.use {
                assertArrayEquals(first, FtpCovers.coverBytes(it))
            }
        } finally {
            transport.close()
        }
    }

    @Test fun `empty archive fails with a message, not IndexOutOfBounds`() {
        val source = FtpZipSource.open(FakeFtpTransport(ZipFixtures.build(emptyList())), "/b.cbz")
        source.use {
            assertTrue(it.pages.isEmpty())
            try {
                FtpCovers.coverBytes(it)
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("no pages") == true)
            }
        }
    }

    @Test fun `giant first entry is refused`() {
        val big = ZipFixtures.pageBytes(3, FtpZipSource.COVER_MAX_BYTES.toInt() + 1)
        val bytes = ZipFixtures.build(listOf(Triple("cover.jpg", ZipEntry.STORED, big)))
        val source = FtpZipSource.open(FakeFtpTransport(bytes), "/b.cbz")
        source.use {
            try {
                FtpCovers.coverBytes(it)
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("cover") == true)
            }
        }
    }

    @Test fun `caller cap below the cover size refuses the transfer`() {
        val source = FtpZipSource.open(FakeFtpTransport(archive()), "/b.cbz")
        source.use {
            try {
                FtpCovers.coverBytes(it, maxBytes = 16)
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("too large") == true)
            }
        }
    }

    private fun archive(): ByteArray = ZipFixtures.build(
        listOf(
            Triple("cover.jpg", ZipEntry.STORED, first),
            Triple("page02.jpg", ZipEntry.STORED, second),
        ),
    )

    private companion object {
        const val USERNAME = "reader"
        const val PASSWORD = "pw"
        const val FILE_NAME = "book.cbz"
        const val PADDING_BYTES = 600_000
    }
}
