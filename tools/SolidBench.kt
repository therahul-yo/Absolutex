// Host benchmark and end-to-end check for the solid-7z cache: the REAL LibArchiveSource, SolidPass
// and native library on the host JVM. Built and run by tools/bench-solid.sh, which supplies the
// android.os stubs; nothing here ships.
//
//   SolidBench <archive.7z> <pagesDir> <workDir> <before|after> [randomJumps]
//
// "before" opens with no cache (today's behaviour); "after" opens with the cache. Every line is
// `label<TAB>milliseconds`, so two runs diff cleanly.
import android.os.ParcelFileDescriptor
import com.absolutex.source.libarchive.LibArchiveSource
import com.absolutex.source.libarchive.SolidCacheConfig
import com.absolutex.source.libarchive.SolidCacheKey
import java.io.File
import java.util.Random

private lateinit var archive: File
private lateinit var pagesDir: File
private lateinit var work: File
private var useCache = false
private var runs = 0

private fun ms(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000
}

private fun report(label: String, millis: Long) = println("$label\t$millis")

private fun key() = SolidCacheKey.of(archive.path, archive.length(), archive.lastModified())

/** A fresh cache root per call, so each cold measurement really is cold. */
private fun freshConfig(): SolidCacheConfig? {
    if (!useCache) return null
    val root = File(work, "cache-${runs++}").also { it.deleteRecursively(); it.mkdirs() }
    return SolidCacheConfig(root, key())
}

private fun open(config: SolidCacheConfig?) =
    LibArchiveSource.open(null, config) { ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY) }

private fun waitForMarker(config: SolidCacheConfig?, sinceMs: Long): Long {
    if (config == null) return 0
    val marker = File(config.root, "solid-archives/${config.key}/complete")
    while (!marker.isFile) Thread.sleep(5)
    return System.currentTimeMillis() - sinceMs
}

private fun expected(index: Int): ByteArray =
    File(pagesDir, "%03d.png".format(index)).readBytes()

private fun cold(label: String, page: Int) {
    val config = freshConfig()
    var bytes = ByteArray(0)
    val total = ms {
        open(config).use { source -> bytes = source.openPage(page).use { it.readBytes() } }
    }
    check(bytes.contentEquals(expected(page))) { "$label returned the wrong bytes" }
    report(label, total)
}

fun main(args: Array<String>) {
    archive = File(args[0])
    pagesDir = File(args[1])
    work = File(args[2]).also { it.mkdirs() }
    useCache = args[3] == "after"
    val jumps = args.getOrNull(4)?.toInt() ?: 20
    val pageCount = pagesDir.listFiles { f -> f.name.endsWith(".png") }!!.size

    // Time to the first page from a cold open, and how long the whole pass takes behind it.
    val config = freshConfig()
    val started = System.currentTimeMillis()
    val source = open(config)
    report("open", System.currentTimeMillis() - started)
    check(source.openPage(0).use { it.readBytes() }.contentEquals(expected(0))) { "page 0 differs" }
    report("first page (open + page 0)", System.currentTimeMillis() - started)
    report("comicinfo available at open", if (source.comicInfo != null) 1 else 0)
    report("pass complete (from open start)", waitForMarker(config, started))
    report("comicinfo after pass", if (source.comicInfo != null) 1 else 0)

    // Random jumps once the pass is done, checked byte for byte.
    val rng = Random(11)
    val picks = List(jumps) { rng.nextInt(pageCount) }
    val times = picks.map { page ->
        ms { check(source.openPage(page).use { it.readBytes() }.contentEquals(expected(page))) { "page $page differs" } }
    }
    report("$jumps random jumps, total", times.sum())
    report("$jumps random jumps, slowest", times.max())
    source.close()

    if (useCache) {
        // The same book again: the finished cache serves it with no decoding at all.
        val again = LibArchiveSource.open(null, SolidCacheConfig(config!!.root, config.key)) {
            ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        report("second open + page 150", ms { again.openPage(150.coerceAtMost(pageCount - 1)).use { it.readBytes() } })
        again.close()
    }

    cold("cold start -> page 150", 150.coerceAtMost(pageCount - 1))
    cold("cold start -> page ${pageCount - 1}", pageCount - 1)

    Thread.sleep(500)
    val leaked = Thread.getAllStackTraces().keys.count { it.name == "absx-solid-pass" && it.isAlive }
    report("pass threads alive after close", leaked.toLong())
}
