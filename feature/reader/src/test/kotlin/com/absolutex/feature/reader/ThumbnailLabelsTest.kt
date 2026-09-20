package com.absolutex.feature.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins what a thumbnail announces (§7, audit A2 and A3).
 *
 * The strip showed bookmark state as a dot and the current page as a border, so neither reached a
 * screen reader, and the bookmark itself was on a long-press TalkBack does not offer. Which string
 * a cell picks is the whole of the A2 fix, and which action it offers is the whole of A3 — so both
 * choices live in functions rather than inline, because a composable cannot be asserted on from a
 * unit test and these are the decisions worth asserting.
 *
 * The current-page half of A1 is not here: it is `selected` in the semantics block, announced by
 * the platform in the platform's words, so there is no string of ours to pin. It needs TalkBack on
 * a device, and is listed as a device check owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbnailLabelsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a bookmarked page says so and an ordinary one does not`() {
        val plain = context.getString(thumbnailLabelRes(bookmarked = false), 3, 45)
        val marked = context.getString(thumbnailLabelRes(bookmarked = true), 3, 45)
        assertNotEquals("a bookmarked page must not sound identical to an unbookmarked one",
            plain, marked)
        assertTrue("both must still say which page: $plain / $marked",
            plain.contains("3") && marked.contains("3"))
    }

    @Test
    fun `both descriptions take the page and the count`() {
        // A translation that drops one of these throws at format time rather than reading oddly,
        // and the crash lands on whoever turned TalkBack on.
        for (bookmarked in listOf(false, true)) {
            val raw = context.getString(thumbnailLabelRes(bookmarked))
            assertTrue("no %1\$d in: $raw", raw.contains("%1\$d"))
            assertTrue("no %2\$d in: $raw", raw.contains("%2\$d"))
        }
    }

    @Test
    fun `the action offers the opposite of the current state`() {
        // An action labelled "Bookmark" on an already-bookmarked page tells the user it will do
        // the thing it just undid.
        assertEquals(
            context.getString(R.string.reader_bookmark_add),
            context.getString(bookmarkActionRes(bookmarked = false)),
        )
        assertEquals(
            context.getString(R.string.reader_bookmark_remove),
            context.getString(bookmarkActionRes(bookmarked = true)),
        )
    }
}
