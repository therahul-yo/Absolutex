package com.absolutex.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScanScopeTest {

    @Test fun `a filesystem root gains a slash boundary`() {
        assertEquals(ScanScope("/sd/Comics", "/sd/Comics/"), ScanScope.of("/sd/Comics"))
    }

    @Test fun `trailing slashes are dropped`() {
        assertEquals(ScanScope("/sd/Comics", "/sd/Comics/"), ScanScope.of("/sd/Comics/"))
        assertEquals(ScanScope("/sd/Comics", "/sd/Comics/"), ScanScope.of("/sd/Comics//"))
    }

    @Test fun `wildcard characters are kept verbatim, not escaped`() {
        // Matching is by substr equality, so nothing needs escaping; escaping here would break it.
        assertEquals(ScanScope("/sd/my_100%", "/sd/my_100%/"), ScanScope.of("/sd/my_100%"))
    }

    @Test fun `a SAF tree uri gets the same boundary`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AComics"
        assertEquals(ScanScope(tree, "$tree/"), ScanScope.of(tree))
    }

    @Test fun `the filesystem root scopes everything absolute`() {
        assertEquals(ScanScope("", "/"), ScanScope.of("/"))
    }

    @Test fun `an empty root is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ScanScope.of("") }
    }
}
