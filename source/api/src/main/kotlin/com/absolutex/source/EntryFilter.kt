package com.absolutex.source

/** Junk entries that appear in real scans and must never become pages (§2). */
object EntryFilter {

    private val JUNK_NAMES = setOf("thumbs.db", ".ds_store", "desktop.ini")
    private val PAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "avif", "heif", "heic", "gif", "bmp", "tif", "tiff",
    )

    fun isPage(entryName: String): Boolean {
        if (isJunk(entryName)) return false
        return extensionOf(entryName) in PAGE_EXTENSIONS
    }

    fun isJunk(entryName: String): Boolean {
        val normalised = entryName.replace('\\', '/')
        if (normalised.endsWith("/")) return true                       // directory entry
        if (normalised.split('/').any { it == "__MACOSX" }) return true
        val base = normalised.substringAfterLast('/')
        if (base.startsWith("._")) return true                          // AppleDouble sidecar
        return base.lowercase() in JUNK_NAMES
    }

    fun extensionOf(entryName: String): String =
        entryName.substringAfterLast('/').substringAfterLast('.', "").lowercase()
}
