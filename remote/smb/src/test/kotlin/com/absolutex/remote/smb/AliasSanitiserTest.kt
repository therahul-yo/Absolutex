package com.absolutex.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alias-to-file mapping must be injective: under the old strip-separators scheme "a/b" and
 * "a\b" both became "a_b" and shared one credential file, sending one server's password to
 * another host. Percent-encoding keeps every distinct alias on its own file.
 */
class AliasSanitiserTest {

    @Test fun `separators never reach the filesystem`() {
        val safe = AndroidKeystoreCredentialStore.sanitiseAlias("nas/comics\\share")
        assertFalse(safe.contains('/'))
        assertFalse(safe.contains('\\'))
    }

    @Test fun `aliases that collided under stripping stay distinct`() {
        val slash = AndroidKeystoreCredentialStore.sanitiseAlias("a/b")
        val backslash = AndroidKeystoreCredentialStore.sanitiseAlias("a\\b")
        val percent = AndroidKeystoreCredentialStore.sanitiseAlias("a%b")
        val escaped = AndroidKeystoreCredentialStore.sanitiseAlias("a%2Fb")
        val distinct = setOf(slash, backslash, percent, escaped)
        assertEquals(4, distinct.size)
    }

    @Test fun `plain aliases pass through readable`() {
        assertEquals("nas-comics", AndroidKeystoreCredentialStore.sanitiseAlias("nas-comics"))
        assertEquals("..%2Fevil", AndroidKeystoreCredentialStore.sanitiseAlias("../evil"))
    }
}
