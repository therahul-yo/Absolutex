package com.absolutex.core.thumbnails

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class ReproduceFlakeTest {
    @Test
    fun `run corrupt source bytes 50 times with Unconfined`() = kotlinx.coroutines.runBlocking(Dispatchers.Unconfined) {
        val tmpDir = File.createTempFile("thumbs", "").parentFile
        repeat(50) { i ->
            val dir = File(tmpDir, "run$i")
            dir.mkdirs()
            val thumbs = ThumbnailPipeline(dir, dispatcher = Dispatchers.Unconfined)
            try {
                val png = testPngBytes(64, 32)
                val truncated = png.copyOf(png.size / 2)
                val source = RepFakeSource(mapOf(0 to truncated))
                val exception = runCatching { thumbs.load(source, ThumbRequest("book-a", 0, ThumbRequest.BUCKET_SMALL)) }.exceptionOrNull()
                assert(exception is java.io.IOException) { "Run $i expected IOException, got $exception" }
            } finally {
                thumbs.close()
            }
        }
    }
}
