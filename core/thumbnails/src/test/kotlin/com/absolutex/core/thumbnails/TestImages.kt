package com.absolutex.core.thumbnails

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Deterministic PNG bytes for tests: pure JVM, no device, no checked-in binaries. */
internal fun testPngBytes(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color(40, 90, 180)
    graphics.fillRect(0, 0, width, height)
    graphics.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}
