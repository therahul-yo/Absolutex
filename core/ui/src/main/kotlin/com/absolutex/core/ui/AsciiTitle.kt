package com.absolutex.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * A section title drawn as character art: each letter a 5×7 pixel glyph, every lit pixel a
 * solid block with a hairline gap, the look of a terminal banner. Monochrome like the rest of
 * the app, and sized to fit the space it is given, never larger than [MAX_CELL] a pixel.
 *
 * Drawn, not typeset: as text, the blocks and the spaces between them came from different
 * fallback fonts at different widths and the columns slipped. The grid is [asciiBanner]'s, so
 * the art is tested on the JVM.
 *
 * Screen readers hear the plain word as a heading, never the blocks. A title with a letter the
 * font does not draw (a translation's accented or non-Latin letters) falls back to plain text in
 * [fallback] rather than drawing a gap.
 */
@Composable
fun AsciiTitle(text: String, fallback: TextStyle, modifier: Modifier = Modifier) {
    val rows = remember(text) { asciiBanner(text) }
    val described = modifier.clearAndSetSemantics {
        contentDescription = text
        heading()
    }
    if (rows == null) {
        Text(text, style = fallback, modifier = described)
        return
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val columns = rows.maxOf { it.length }
    Canvas(
        described.layout { measurable, constraints ->
            val cell = minOf(
                MAX_CELL.toPx(),
                constraints.maxWidth.toFloat() / columns,
                constraints.maxHeight.toFloat() / rows.size,
            )
            val width = (cell * columns).toInt()
            val height = (cell * rows.size).toInt()
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        },
    ) {
        val cell = size.width / columns
        val block = Size(cell * BLOCK_FILL, cell * BLOCK_FILL)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c -> if (c == FULL) drawRect(ink, Offset(x * cell, y * cell), block) }
        }
    }
}

/** The banner's rows, or null when [text] holds a letter the font does not draw. */
fun asciiBanner(text: String): List<String>? {
    val glyphs = text.uppercase().map { c -> if (c == ' ') SPACE else GLYPHS[c] ?: return null }
    return (0 until GLYPH_ROWS).map { row ->
        glyphs.joinToString(" ") { glyph -> glyph[row].map { if (it == '1') FULL else ' ' }.joinToString("") }
            .trimEnd()
    }
}

private const val FULL = '█'
private const val GLYPH_ROWS = 7
/** The largest a pixel of the banner is drawn. */
private val MAX_CELL = 3.5.dp

/** A lit pixel fills this much of its cell, so the blocks read as blocks with a hairline gap. */
private const val BLOCK_FILL = 0.88f

private val SPACE = List(GLYPH_ROWS) { "000" }

/** 5×7 capitals, top row first, 1 for a lit pixel. */
private val GLYPHS: Map<Char, List<String>> = mapOf(
    'A' to "01110/10001/10001/11111/10001/10001/10001",
    'B' to "11110/10001/10001/11110/10001/10001/11110",
    'C' to "01111/10000/10000/10000/10000/10000/01111",
    'D' to "11110/10001/10001/10001/10001/10001/11110",
    'E' to "11111/10000/10000/11110/10000/10000/11111",
    'F' to "11111/10000/10000/11110/10000/10000/10000",
    'G' to "01111/10000/10000/10011/10001/10001/01111",
    'H' to "10001/10001/10001/11111/10001/10001/10001",
    'I' to "11111/00100/00100/00100/00100/00100/11111",
    'J' to "00111/00010/00010/00010/00010/10010/01100",
    'K' to "10001/10010/10100/11000/10100/10010/10001",
    'L' to "10000/10000/10000/10000/10000/10000/11111",
    'M' to "10001/11011/10101/10101/10001/10001/10001",
    'N' to "10001/11001/10101/10011/10001/10001/10001",
    'O' to "01110/10001/10001/10001/10001/10001/01110",
    'P' to "11110/10001/10001/11110/10000/10000/10000",
    'Q' to "01110/10001/10001/10001/10101/10010/01101",
    'R' to "11110/10001/10001/11110/10100/10010/10001",
    'S' to "01111/10000/10000/01110/00001/00001/11110",
    'T' to "11111/00100/00100/00100/00100/00100/00100",
    'U' to "10001/10001/10001/10001/10001/10001/01110",
    'V' to "10001/10001/10001/10001/10001/01010/00100",
    'W' to "10001/10001/10001/10101/10101/10101/01010",
    'X' to "10001/10001/01010/00100/01010/10001/10001",
    'Y' to "10001/10001/01010/00100/00100/00100/00100",
    'Z' to "11111/00001/00010/00100/01000/10000/11111",
).mapValues { it.value.split('/') }
