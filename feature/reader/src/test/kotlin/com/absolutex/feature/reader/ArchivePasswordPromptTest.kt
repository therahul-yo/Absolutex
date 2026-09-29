package com.absolutex.feature.reader

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.settings.InMemorySettings
import com.absolutex.model.BookIdentity
import com.absolutex.model.Page
import com.absolutex.remote.core.RemoteBookOpener
import com.absolutex.remote.core.RemoteOpenResult
import com.absolutex.source.ComicSource
import com.absolutex.source.libarchive.ArchivePasswordException
import com.absolutex.source.libarchive.PasswordRequiredException
import com.absolutex.source.libarchive.UnsupportedEncryptionException
import com.absolutex.source.libarchive.WrongPasswordException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * The encrypted-archive password flow at the reader boundary: the same prompt and retry loop the
 * encrypted PDF uses, driven by the libarchive exceptions against a fake opener. No native code
 * and no device — the real-archive checks (encrypted CBZ, RAR5, 7z) are instrumented-only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchivePasswordPromptTest {

    @get:Rule val main = MainDispatcherRule()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { body() }

    private fun vm(opener: BookOpener): ReaderViewModel {
        val settings = InMemorySettings()
        return ReaderViewModel(
            context = ApplicationProvider.getApplicationContext(),
            progressDao = NoProgress(),
            totalRamBytes = RAM_BYTES,
            prefs = settings,
            rendering = settings,
            appPrefs = settings,
            bookOpener = opener,
            remoteBookOpener = NoRemote(),
        )
    }

    private fun uri(name: String): Uri = Uri.parse("content://books/$name")

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test fun `an encrypted archive prompts for a password instead of failing`() = test {
        val opener = ArchiveOpener(correct = "secret")
        val vm = vm(opener)
        vm.open(uri("locked.cbz"))
        advanceUntilIdle()

        val state = vm.ui.value
        assertTrue("must prompt", state.passwordRequired)
        assertFalse("first prompt is not a retry", state.passwordIncorrect)
        assertFalse("the prompt must not spin", state.loading)
        assertEquals(string(R.string.reader_open_failed), state.error)
        assertEquals("nothing may stay open behind the prompt", 0, opener.openHandles)
    }

    @Test fun `a wrong password keeps the prompt up as incorrect, then the right one opens`() = test {
        val opener = ArchiveOpener(correct = "secret")
        val vm = vm(opener)
        vm.open(uri("locked.cbz"))
        advanceUntilIdle()

        vm.open(uri("locked.cbz"), "guess")
        advanceUntilIdle()
        assertTrue("the dialog must stay", vm.ui.value.passwordRequired)
        assertTrue("a refused password must read as incorrect", vm.ui.value.passwordIncorrect)
        assertFalse(vm.ui.value.loading)
        assertEquals(0, opener.openHandles)

        vm.open(uri("locked.cbz"), "secret")
        advanceUntilIdle()
        assertEquals(listOf(null, "guess", "secret"), opener.attempts)
        assertFalse("the prompt must be gone", vm.ui.value.passwordRequired)
        assertFalse(vm.ui.value.passwordIncorrect)
        assertNull(vm.ui.value.error)
        assertEquals(1, vm.ui.value.pageCount)
        assertEquals("only the opened book holds a handle", 1, opener.openHandles)
    }

    @Test fun `a password the archive still asks for again also reads as incorrect`() = test {
        // Header-encrypted archives answer "required" even to a wrong password.
        val vm = vm(ArchiveOpener(correct = "secret", wrongIsRequired = true))
        vm.open(uri("locked.7z"), "guess")
        advanceUntilIdle()

        assertTrue(vm.ui.value.passwordRequired)
        assertTrue(vm.ui.value.passwordIncorrect)
    }

    @Test fun `the password never lands in the ui state`() = test {
        val vm = vm(ArchiveOpener(correct = "hunter2-secret"))
        vm.open(uri("locked.cbr"), "hunter2-wrong")
        advanceUntilIdle()
        assertFalse(vm.ui.value.toString().contains("hunter2"))

        vm.open(uri("locked.cbr"), "hunter2-secret")
        advanceUntilIdle()
        assertFalse(vm.ui.value.toString().contains("hunter2"))
    }

    @Test fun `cancelling the prompt leaves the reader with no error and does not retry`() = test {
        val opener = ArchiveOpener(correct = "secret")
        val vm = vm(opener)
        vm.open(uri("locked.cbz"))
        advanceUntilIdle()

        vm.cancelPasswordPrompt()
        advanceUntilIdle()

        assertFalse("the prompt must be gone", vm.ui.value.passwordRequired)
        assertFalse(vm.ui.value.loading)
        assertNull("cancelling is a choice, not a failure", vm.ui.value.error)
        assertEquals("cancel emits exactly one leave event", Unit, vm.leave.first())
        assertNull("and only one", withTimeoutOrNull(1) { vm.leave.first() })
        assertEquals("cancel must not retry the open", listOf<String?>(null), opener.attempts)
        assertEquals(0, opener.openHandles)
    }

    @Test fun `a wrong password or a correct one never emits the leave event`() = test {
        val vm = vm(ArchiveOpener(correct = "secret"))
        vm.open(uri("locked.cbz"))
        advanceUntilIdle()
        vm.open(uri("locked.cbz"), "guess")
        advanceUntilIdle()
        assertTrue(vm.ui.value.passwordRequired)
        vm.open(uri("locked.cbz"), "secret")
        advanceUntilIdle()

        assertEquals(1, vm.ui.value.pageCount)
        assertNull(withTimeoutOrNull(1) { vm.leave.first() })
    }

    @Test fun `cancelling with no prompt showing does nothing`() = test {
        val vm = vm(ArchiveOpener(correct = "secret"))
        vm.open(uri("locked.cbz"), "secret")
        advanceUntilIdle()

        vm.cancelPasswordPrompt()

        assertEquals("an open book stays open", 1, vm.ui.value.pageCount)
        assertNull(withTimeoutOrNull(1) { vm.leave.first() })
    }

    @Test fun `unsupported encryption is terminal - a clear message and no prompt`() = test {
        val opener = ArchiveOpener(correct = "secret", failure = UnsupportedEncryptionException("aes"))
        val vm = vm(opener)
        vm.open(uri("odd.cb7"))
        advanceUntilIdle()

        val state = vm.ui.value
        assertFalse("no password can help, so no prompt", state.passwordRequired)
        assertFalse(state.loading)
        assertEquals(string(R.string.reader_open_unsupported_encryption), state.error)
        assertEquals(listOf<String?>(null), opener.attempts)
        assertEquals(0, opener.openHandles)
    }

    @Test fun `every failed attempt closes what it half-opened`() = test {
        val opener = ArchiveOpener(correct = "secret")
        val vm = vm(opener)
        for (password in listOf(null, "a", "b", "c")) {
            vm.open(uri("locked.cbz"), password)
            advanceUntilIdle()
            assertEquals("after attempt with $password", 0, opener.openHandles)
        }
        assertEquals(4, opener.opened)
    }

    @Test fun `a corrupt archive is the generic failure, not a prompt`() = test {
        val vm = vm(ArchiveOpener(correct = "secret", failure = IOException("not a readable archive")))
        vm.open(uri("broken.cbz"))
        advanceUntilIdle()

        assertFalse(vm.ui.value.passwordRequired)
        assertFalse(vm.ui.value.loading)
        assertEquals(string(R.string.reader_open_failed), vm.ui.value.error)
    }

    // ---- withPasswordChars: the String -> CharArray hand-off in openBook ----------------

    @Test fun `the char copy is zeroed after use`() {
        var seen: CharArray? = null
        val result = withPasswordChars("secret") { chars ->
            seen = chars
            assertEquals("secret", String(chars))
            "done"
        }
        assertEquals("done", result)
        assertTrue("the array must be wiped", seen!!.all { it == '\u0000' })
    }

    @Test fun `the char copy is zeroed even when the open throws`() {
        var seen: CharArray? = null
        try {
            withPasswordChars<Unit>("secret") { chars ->
                seen = chars
                throw PasswordRequiredException("boom")
            }
            fail("must rethrow")
        } catch (expected: ArchivePasswordException) {
            assertNotNull(expected)
        }
        assertTrue("the array must be wiped", seen!!.all { it == '\u0000' })
    }

    @Test fun `a password the archive api cannot take reads as wrong without reaching it`() {
        for (bad in listOf("", "ab\u0000cd")) {
            var called = false
            try {
                withPasswordChars(bad) { called = true }
                fail("must refuse")
            } catch (expected: WrongPasswordException) {
                assertNotNull(expected)
            }
            assertFalse(called)
        }
    }

    private companion object {
        const val RAM_BYTES = 4L * 1024 * 1024 * 1024
    }
}

/**
 * A [BookOpener] for one encrypted archive. Like `LibArchiveSource.open`, every attempt takes a
 * handle (the descriptor and the passphrase copy) and gives it back before throwing, so
 * [openHandles] is the number of books actually open, never the number of attempts.
 */
private class ArchiveOpener(
    val correct: String,
    val wrongIsRequired: Boolean = false,
    val failure: IOException? = null,
) : BookOpener {
    val attempts = mutableListOf<String?>()
    var opened = 0
        private set
    var openHandles = 0
        private set

    override suspend fun open(uri: Uri, password: String?): Pair<Closeable, String> {
        attempts += password
        opened++
        openHandles++
        val source = object : ComicSource {
            override val pages = listOf(Page(0, "page0.jpg"))
            override fun openPage(index: Int): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun close() {
                openHandles--
            }
        }
        val refusal: IOException? = when {
            failure != null -> failure
            password == null -> PasswordRequiredException("password required")
            password != correct -> if (wrongIsRequired) {
                PasswordRequiredException("password required")
            } else {
                WrongPasswordException("wrong password")
            }
            else -> null
        }
        if (refusal != null) {
            source.close()
            throw refusal
        }
        return source to BookIdentity.of(uri.lastPathSegment.orEmpty(), 1L)
    }
}

private class NoProgress : ProgressDao {
    override suspend fun get(bookId: String): ReadingProgress? = null
    override fun observe(bookId: String): Flow<ReadingProgress?> = flowOf(null)
    override suspend fun upsert(progress: ReadingProgress) = Unit
    override suspend fun mostRecent(): ReadingProgress? = null
    override fun observeAll(): Flow<List<ReadingProgress>> = flowOf(emptyList())
}

private class NoRemote : RemoteBookOpener {
    override suspend fun open(uri: String): RemoteOpenResult = throw IOException("no remote in this test")
}
