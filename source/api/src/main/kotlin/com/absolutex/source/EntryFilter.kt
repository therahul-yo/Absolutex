package com.absolutex.source

import java.util.Locale

/** Junk entries that appear in real scans and must never become pages (§2). */
object EntryFilter {

    private val JUNK_NAMES = setOf("thumbs.db", ".ds_store", "desktop.ini")
    private val PAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "avif", "heif", "heic", "gif", "bmp", "tif", "tiff",
        // Phase 4: modern/high-bit-depth scan formats (JPEG XL, JPEG 2000 family).
        "jxl", "jp2", "jpx", "j2k",
    )

    fun isPage(entryName: String): Boolean {
        if (isJunk(entryName)) return false
        return extensionOf(entryName) in PAGE_EXTENSIONS
    }

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
