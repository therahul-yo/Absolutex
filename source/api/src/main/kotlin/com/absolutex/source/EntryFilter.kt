package com.absolutex.source

import java.util.Locale

/** Junk entries that appear in real scans and must never become pages (§2). */
object EntryFilter {

    private val JUNK_NAMES = setOf("thumbs.db", ".ds_store", "desktop.ini")

    /** Directories that are archive or filesystem bookkeeping, never part of a book. */
    private val JUNK_DIRECTORIES = setOf("__macosx")
    private val PAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "avif", "heif", "heic", "gif", "bmp", "tif", "tiff",
        // Phase 4: modern/high-bit-depth scan formats (JPEG XL, JPEG 2000 family).
        "jxl", "jp2", "jpx", "j2k",
    )

    fun isPage(entryName: String): Boolean {
        if (isJunk(entryName)) return false
        return extensionOf(entryName) in PAGE_EXTENSIONS
    }

    /**
     * True for a directory that is bookkeeping rather than content.
     *
     * Separate from [isJunk] because that one answers "is this entry junk" for a path, and every
     * directory path ends in "/" which it already rejects. A walker needs to ask about the
     * directory itself before descending — filtering only files lets "__MACOSX/001.cbz" through.
     */
    fun isJunkDirectory(name: String): Boolean = name.lowercase() in JUNK_DIRECTORIES

    fun isJunk(entryName: String): Boolean {
        val normalised = entryName.replace('\\', '/')
        if (normalised.endsWith("/")) return true                       // directory entry
        // Phase 4: case-insensitive — some zippers emit __MacOSX/__macosx.
        if (normalised.split('/').any { it.lowercase(Locale.ROOT) == "__macosx" }) return true
        val base = normalised.substringAfterLast('/')
        if (base.startsWith("._")) return true                          // AppleDouble sidecar
        // Locale.ROOT: default-locale lowercase (tr/AZ dotted-I) must never change matching.
        return base.lowercase(Locale.ROOT) in JUNK_NAMES
    }

    fun extensionOf(entryName: String): String =
        // Phase 4: backslash first — a Windows-style "ch1\page01.JPG" has no '/' to split on.
        entryName.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
}
