package com.absolutex.feature.remote

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.absolutex.remote.sync.FtpServer
import com.absolutex.remote.sync.InMemoryCredentialStore
import com.absolutex.remote.sync.KomgaServer
import com.absolutex.remote.sync.RemoteServers
import com.absolutex.remote.sync.SmbServer
import com.absolutex.remote.sync.SyncSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Listing dispatch without fakes where the seams allow it: error paths and record roots
 * over the real store, the FTP branch against a real in-process server (files on disk,
 * parsed listings, real filtering and sorting). The SMB branch dials on list and has no
 * in-process server — its mapping and retry policy are pinned in `:remote:smb`'s
 * [com.absolutex.remote.smb.SmbListTest], and the wire stays lead-verified on a real NAS
 * like every other SMBJ path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteListingTest {

    @get:Rule val tmp = TemporaryFolder()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After fun resetMain() {
        Dispatchers.resetMain()
    }

    private fun dataStore(name: String) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + Job()),
        produceFile = { File(tmp.root, name) },
    )

    private suspend fun seeded(): RemoteListingResolver {
        val servers = RemoteServers(dataStore("remote.preferences_pb"))
        val secrets = SyncSecrets(InMemoryCredentialStore())
        servers.save(SmbServer("smb", "nas", "comics", "/books", 445, "u"))
        servers.save(FtpServer("ftp", "nas", 21, "/pub", "u", useTls = false))
        servers.save(KomgaServer("komga", "https://k.lan", username = "u"))
        return RemoteListingResolver(servers, SmbBookBackend(secrets), FtpBookBackend(secrets))
    }

    private suspend fun failureOf(op: suspend () -> Unit): IOException = try {
        op()
        fail("expected IOException")
        throw AssertionError("unreachable")
    } catch (e: IOException) {
        e
    }

    @Test fun `unknown and sync servers fail as IOException`() = runTest {
        val resolver = seeded()
        assertTrue(failureOf { resolver.listDir("nope", "/") }.message?.contains("unknown") == true)
        assertTrue(failureOf { resolver.listDir("komga", "/") }.message?.contains("no files") == true)
        assertTrue(failureOf { resolver.rootPath("nope") }.message?.contains("unknown") == true)
        assertTrue(failureOf { resolver.rootPath("komga") }.message?.contains("no files") == true)
    }

    @Test fun `escaping paths never reach a transport`() = runTest {
        val resolver = seeded()
        for (path in listOf("/../secret.cbz", "relative/b.cbz", "/a/../../b.cbz")) {
            val failure = failureOf { resolver.listDir("ftp", path) }
            assertTrue("path $path: ${failure.message}", failure.message?.contains("invalid") == true)
        }
    }

    @Test fun `root paths come from the records`() = runTest {
        val resolver = seeded()
        assertEquals("/books", resolver.rootPath("smb"))
        assertEquals("/pub", resolver.rootPath("ftp"))
    }

    @Test fun `display rows de-duplicate by path and sieve hostile names`() {
        // A rename-and-replace mid-listing genuinely repeats a name (SMB2 enumeration
        // is no snapshot); without distinctBy the duplicate Compose key throws. The
        // sieve is backend-blind: FTP rows never saw SMB's mapper, so `..` dies here.
        val rows = listOf(
            RemoteEntry("a.cbz", "/a.cbz", false, 1L),
            RemoteEntry("a.cbz", "/a.cbz", false, 1L),
            RemoteEntry("..", "/..", true, 0L),
            RemoteEntry(".", "/.", true, 0L),
            RemoteEntry("", "/", false, 0L),
            RemoteEntry("x".repeat(300), "/long", false, 1L),
            RemoteEntry("a/b", "/a/b", false, 1L),
            RemoteEntry("sub", "/sub", true, 0L),
        ).toDisplayRows()
        assertEquals(listOf("sub", "a.cbz"), rows.map { it.name })
    }

    @Test fun `display rows keep folders and books, folders first by name`() {
        val rows = listOf(
            RemoteEntry("notes.txt", "/notes.txt", false, 1L),
            RemoteEntry("Zebra.cbz", "/Zebra.cbz", false, 1L),
            RemoteEntry("apple.cbz", "/apple.cbz", false, 1L),
            RemoteEntry("sub", "/sub", true, 0L),
            RemoteEntry("Archive.CBR", "/Archive.CBR", false, 1L),
        ).toDisplayRows()
        assertEquals(
            listOf("sub", "apple.cbz", "Archive.CBR", "Zebra.cbz"),
            rows.map { it.name },
        )
    }

    @Test fun `join keeps absolute children`() {
        assertEquals("/books/a.cbz", join("/books", "a.cbz"))
        assertEquals("/a.cbz", join("/", "a.cbz"))
    }

    @Test fun `ftp lists a real server with real filtering`() = runTest {
        val root = Files.createTempDirectory("ftp-browse-test")
        Files.createDirectory(root.resolve("sub"))
        Files.write(root.resolve("book.cbz"), ByteArray(64))
        Files.write(root.resolve("notes.txt"), "not a book".toByteArray())
        val (port, server) = startFtpServer(root)
        try {
            val servers = RemoteServers(dataStore("ftp.preferences_pb"))
            val secrets = SyncSecrets(InMemoryCredentialStore())
            servers.save(FtpServer("ftp", "127.0.0.1", port, "/", "reader", useTls = false))
            secrets.saveFtpPassword("ftp", "pw".toCharArray())
            val resolver = RemoteListingResolver(servers, SmbBookBackend(secrets), FtpBookBackend(secrets))
            assertEquals("/", resolver.rootPath("ftp"))
            val rows = resolver.listDir("ftp", "/")
            // The text file is filtered, the folder sorts first, paths stay absolute.
            assertEquals(
                listOf(
                    RemoteEntry("sub", "/sub", true, 0L),
                    rows.single { it.name == "book.cbz" },
                ),
                rows,
            )
            assertTrue(rows.none { it.name == "notes.txt" })
            val book = rows.single { it.name == "book.cbz" }
            assertEquals("/book.cbz", book.path)
        } finally {
            server.stop()
        }
    }

    private fun startFtpServer(root: java.nio.file.Path): Pair<Int, org.apache.ftpserver.FtpServer> {
        val userManager = org.apache.ftpserver.usermanager.PropertiesUserManagerFactory()
            .createUserManager()
        val user = org.apache.ftpserver.usermanager.impl.BaseUser().apply {
            name = "reader"
            password = "pw"
            homeDirectory = root.toString()
            authorities = listOf(org.apache.ftpserver.usermanager.impl.WritePermission())
        }
        userManager.save(user)
        val factory = org.apache.ftpserver.FtpServerFactory().apply {
            val listener = org.apache.ftpserver.listener.ListenerFactory().apply { port = 0 }.createListener()
            addListener("default", listener)
            setUserManager(userManager)
        }
        val server = factory.createServer().also { it.start() }
        return factory.getListener("default")!!.port to server
    }
}
