package com.absolutex.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The reading stylesheet is spliced into every chapter, and chapters are XHTML: an angle bracket
 * or an ampersand anywhere in it, even inside a CSS comment, makes the whole chapter a parse error
 * on the device. It happened once, from a comment that mentioned a paragraph tag.
 */
class ReadingCssTest {

    @Test fun `the stylesheet holds no markup in either mode`() {
        for (scroll in listOf(false, true)) {
            val css = readingCss(PageBox(fontPx = 18, width = 360, height = 780, scroll = scroll))
            assertFalse("scroll=$scroll", css.contains('<') || css.contains('&'))
        }
    }
}
