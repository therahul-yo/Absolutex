package com.absolutex.remote.sync

import com.absolutex.remote.core.InMemoryCredentialStore
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTripCopiesSecrets() {
        val store = InMemoryCredentialStore()
        val original = "s3cret".toCharArray()
        store.save("komga", original)
        original.fill('x')
        assertArrayEquals("s3cret".toCharArray(), store.load("komga"))
    }

    @Test fun clearRemoves() {
        val store = InMemoryCredentialStore()
        store.save("komga", "s3cret".toCharArray())
        store.clear("komga")
        assertNull(store.load("komga"))
    }

    @Test fun overwriteReplaces() {
        val store = InMemoryCredentialStore()
        store.save("komga", "old".toCharArray())
        store.save("komga", "new".toCharArray())
        assertArrayEquals("new".toCharArray(), store.load("komga"))
    }

    // Services that both stripped to "komganas1" under the old isLetterOrDigit filter must now
    // hash to different aliases/file names, or one server's secret silently overwrites another's.
    @Test fun previouslyCollidingServicesNowHashDifferently() {
        assertNotEquals(hashService("komga:nas-1"), hashService("komganas1"))
        assertNotEquals(hashService("kavita:a.b"), hashService("kavita:ab"))
    }

    @Test fun hashServiceIsDeterministic() {
        assertEquals(hashService("komga:nas-1"), hashService("komga:nas-1"))
    }

    @Test fun hashServiceIsFullSha256Hex() {
        assertEquals(64, hashService("komga:nas-1").length)
        assertTrue(hashService("komga:nas-1").all { it in "0123456789abcdef" })
    }

    @Test fun writeFileAtomicallyProducesTargetWithoutLeavingTempFile() {
        val target = File(tmp.root, "cred.bin")
        writeFileAtomically(target, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun writeFileAtomicallyReplacesExistingTargetWholesale() {
        val target = File(tmp.root, "cred.bin")
        writeFileAtomically(target, byteArrayOf(1, 2, 3, 4, 5))
        // A second write must fully replace the first, never interleave/truncate it -- that's
        // exactly the "torn write" this helper exists to prevent.
        writeFileAtomically(target, byteArrayOf(9, 9))
        assertArrayEquals(byteArrayOf(9, 9), target.readBytes())
    }
}
