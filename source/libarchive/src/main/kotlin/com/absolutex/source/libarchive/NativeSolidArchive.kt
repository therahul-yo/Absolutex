package com.absolutex.source.libarchive

import android.os.ParcelFileDescriptor
import com.absolutex.source.ComicInfoLoader
import java.io.File

/** The native half of the solid cache: [EntryStreamer] and the probe, each on a fresh descriptor. */
internal class NativeSolidArchive(
    private val openFd: () -> ParcelFileDescriptor,
    private val passphrase: ArchivePassphrase,
) : EntryStreamer {
    fun probe(): SolidProbe {
        val total = LongArray(1)
        val solid = openFd().use { LibArchive.nativeProbeSolid(it.fd, total) }
        return SolidProbe(solid, total[0])
    }

    override fun stream(wanted: BooleanArray, maxBytes: Long, sink: EntrySink): PassEnd {
        val code = passphrase.useBytes { password ->
            openFd().use { LibArchive.nativeStreamEntries(it.fd, wanted, maxBytes, password, sink) }
        }
        return PassEnd.fromCode(code)
    }

    /** Starts a pass on a low-priority daemon thread: it must never keep the process or the UI busy. */
    fun launch(cacheRoot: File): SolidLaunch = SolidLaunch(
        probe = ::probe,
        streamer = this,
        freeBytes = { cacheRoot.usableSpace },
        spawn = { pass ->
            Thread(null, pass, PASS_THREAD).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }.start()
        },
    )

    private companion object {
        const val PASS_THREAD = "absx-solid-pass"
    }
}

/**
 * The cache for the book being opened (its raw entry names in [raw], its pages' ordinals in
 * [pageOrdinals]), or null to read it directly. See [startSolidCache].
 */
internal fun startNativeSolidCache(
    cache: SolidCacheConfig,
    archive: NativeSolidArchive,
    raw: Array<ByteArray>,
    pageOrdinals: IntArray,
): SolidSession? {
    val info = raw.indexOfFirst { ComicInfoLoader.isComicInfoName(String(it, Charsets.UTF_8)) }
    return startSolidCache(cache, SolidBook(raw.size, pageOrdinals, info), archive.launch(cache.root))
}
