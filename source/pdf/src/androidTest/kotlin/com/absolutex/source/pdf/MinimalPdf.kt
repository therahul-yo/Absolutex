package com.absolutex.source.pdf

import java.io.ByteArrayOutputStream

/**
 * Builds a valid single-page PDF in memory so the instrumented tests carry no binary fixture.
 *
 * The page is [WIDTH_PT] x [HEIGHT_PT] points with one opaque red rectangle on it. Red is
 * chosen deliberately: it is asymmetric under a red/blue channel swap, so a pixel assertion
 * against it catches a BGRA-vs-RGBA mistake in the render path, which a grey or white test
 * page would hide.
 */
internal object MinimalPdf {

    const val WIDTH_PT = 612f
    const val HEIGHT_PT = 792f

    /** Red rectangle in PDF user space (origin bottom-left). */
    const val RECT_X = 100
    const val RECT_Y = 100
    const val RECT_W = 200
    const val RECT_H = 300

    fun singleRedRectPage(): ByteArray {
        val content = "1 0 0 rg\n$RECT_X $RECT_Y $RECT_W $RECT_H re\nf\n".toByteArray(Charsets.US_ASCII)

        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${WIDTH_PT.toInt()} ${HEIGHT_PT.toInt()}] " +
                "/Contents 4 0 R /Resources << >> >>",
            "<< /Length ${content.size} >>\nstream\n${content.toString(Charsets.US_ASCII)}endstream",
        )

        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

        write("%PDF-1.4\n")
        // Offsets must be byte-accurate or PDFium rejects the xref table, so they are
        // recorded as the body is written rather than computed by hand.
        val offsets = IntArray(objects.size + 1)
        objects.forEachIndexed { i, body ->
            offsets[i + 1] = out.size()
            write("${i + 1} 0 obj\n$body\nendobj\n")
        }

        val xrefOffset = out.size()
        write("xref\n0 ${objects.size + 1}\n")
        write("0000000000 65535 f \n")
        for (i in 1..objects.size) {
            write("%010d 00000 n \n".format(offsets[i]))
        }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")

        return out.toByteArray()
    }
}
