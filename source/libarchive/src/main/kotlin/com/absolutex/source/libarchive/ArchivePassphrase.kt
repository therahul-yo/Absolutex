package com.absolutex.source.libarchive

import java.io.Closeable
import java.io.IOException
import java.nio.CharBuffer

/** Owned UTF-8 bytes; never an immutable String, preference, log value or global cache. */
internal class ArchivePassphrase(chars: CharArray?) : Closeable {
    private var bytes: ByteArray? = chars?.let {
        require(it.isNotEmpty() && '\u0000' !in it) { "Password must be nonempty and contain no NUL" }
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(it))
        try {
            ByteArray(encoded.remaining()).also(encoded::get)
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }
    private var closed = false

    /** Snapshot only under the monitor; native reads remain independent, not serialized. */
    fun <T> useBytes(block: (ByteArray?) -> T): T {
        val snapshot = synchronized(this) {
            if (closed) throw IOException("Archive source is closed")
            bytes?.copyOf()
        }
        return try {
            block(snapshot)
        } finally {
            snapshot?.fill(0)
        }
    }

    @Synchronized fun forget() {
        bytes?.fill(0)
        bytes = null
    }

    @Synchronized override fun close() {
        forget()
        closed = true
    }
}
