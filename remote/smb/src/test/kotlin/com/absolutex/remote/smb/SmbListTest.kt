package com.absolutex.remote.smb

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.msdtyp.FileTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * SMB folder listing without a network. The mapping runs over genuine smbj rows — built
 * through the real (package-private) constructor via reflection, never hand-made doubles —
 * so the directory bit, the size field and every sieve are asserted against the types the
 * wire actually produces. Transport policy (reconnect-once, terminal close) rides the same
 * fake-connection pattern as [SmbjTransportTest].
 */
class SmbListTest {

    private val location = SmbLocation(
        host = "nas",
        share = "comics",
        path = "books/b.cbz",
        port = 445,
        username = "reader",
    )

    private class FakeConnection(
        val entries: List<SmbEntry>,
        val failures: Int = 0,
    ) : SmbConnection {
        var lists = 0
        var closes = 0

        override fun openFile(remotePath: String): RemoteFileHandle {
            throw IOException("listing tests never open files")
        }

        override fun listDir(remotePath: String): List<SmbEntry> {
            lists++
            if (lists <= failures) throw IOException("connection lost")
            return entries
        }

        override fun close() {
            closes++
        }
    }

    private class FakeConnector(val connection: FakeConnection) : SmbConnector {
        var connects = 0

        override fun connect(password: CharArray): SmbConnection {
            connects++
            return connection
        }
    }

    private fun transport(connection: FakeConnection): SmbjTransport {
        val credentials = InMemoryCredentialStore().apply { store("nas", "secret".toCharArray()) }
        return SmbjTransport(location, credentials, "nas", FakeConnector(connection))
    }

    /**
     * One genuine smbj listing row. The constructor is package-private, so reflection is
     * the only way to build one — the field order below is verified against the 0.15.0
     * bytecode (endOfFile eighth, fileAttributes tenth), and the round-trip assertions
     * below would fail loudly if a smbj upgrade ever reordered them.
     */
    private fun smbRow(name: String, isDirectory: Boolean, size: Long): FileIdBothDirectoryInformation {
        val ctor = FileIdBothDirectoryInformation::class.java.getDeclaredConstructor(
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            String::class.java,
            FileTime::class.java,
            FileTime::class.java,
            FileTime::class.java,
            FileTime::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            String::class.java,
            java.lang.Long.TYPE,
        )
        ctor.isAccessible = true
        val attributes = if (isDirectory) DIRECTORY_BIT else ARCHIVE_BIT
        return ctor.newInstance(
            0L, 0L, name, null, null, null, null, size, size, attributes, 0L, "", 0L,
        ) as FileIdBothDirectoryInformation
    }

    @Test fun `mapping reads the real directory bit and size`() {
        val mapped = mapSmbEntries(
            listOf(
                smbRow("comics", true, 0L),
                smbRow("book.cbz", false, 4096L),
            ),
        )
        assertEquals(
            listOf(SmbEntry("comics", true, 0L), SmbEntry("book.cbz", false, 4096L)),
            mapped,
        )
    }

    @Test fun `dot entries blanks and hostile names never become entries`() {
        val hostile = "x".repeat(MAX_SMB_ENTRY_NAME_LENGTH + 1)
        val mapped = mapSmbEntries(
            listOf(
                smbRow(".", true, 0L),
                smbRow("..", true, 0L),
                smbRow("", false, 1L),
                smbRow("..", false, 1L),
                smbRow(hostile, false, 1L),
                smbRow("sub/dir.cbz", false, 1L),
                smbRow("sub\\dir.cbz", false, 1L),
                smbRow("ok.cbz", false, 7L),
            ),
        )
        assertEquals(listOf(SmbEntry("ok.cbz", false, 7L)), mapped)
    }

    @Test fun `boundary-length names still list`() {
        val edge = "y".repeat(MAX_SMB_ENTRY_NAME_LENGTH)
        val mapped = mapSmbEntries(listOf(smbRow(edge, false, 3L)))
        assertEquals(listOf(SmbEntry(edge, false, 3L)), mapped)
    }

    @Test fun `transport delegates listing to the live share`() {
        val entries = listOf(SmbEntry("a.cbz", false, 1L))
        val connection = FakeConnection(entries)
        val listed = transport(connection).listDir("/books")
        assertEquals(entries, listed)
        assertEquals(1, connection.lists)
    }

    @Test fun `a listing failure reconnects once and succeeds`() {
        val entries = listOf(SmbEntry("a.cbz", false, 1L))
        val connection = FakeConnection(entries, failures = 1)
        val listed = transport(connection).listDir("/books")
        assertEquals(entries, listed)
        assertEquals(2, connection.lists)
    }

    @Test fun `a second listing failure propagates with both attempts recorded`() {
        val connection = FakeConnection(emptyList(), failures = Int.MAX_VALUE)
        try {
            transport(connection).listDir("/books")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertEquals(2, connection.lists)
            assertTrue(expected.suppressed.isNotEmpty())
        }
    }

    @Test fun `listing after close throws without connecting`() {
        val connection = FakeConnection(emptyList())
        val connector = FakeConnector(connection)
        val credentials = InMemoryCredentialStore().apply { store("nas", "secret".toCharArray()) }
        val transport = SmbjTransport(location, credentials, "nas", connector)
        transport.close()
        try {
            transport.listDir("/books")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("closed") == true)
        }
        assertEquals(0, connector.connects)
    }

    private companion object {
        // MS-FSCC FileAttributes bits, matching FileAttributes.FILE_ATTRIBUTE_* values.
        const val DIRECTORY_BIT = 0x10L
        const val ARCHIVE_BIT = 0x20L
    }
}
