package com.absolutex.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.absolutex.source.EntryFilter
import java.io.File

/**
 * MediaStore reads behind [MediaStoreObserver]; one seam so tests fake rows while the
 * platform query code stays in exactly one place.
 */
interface MediaRowSource {
    /** Filesystem path for a MediaStore row, or null when the row is already gone. */
    fun rowFor(id: Long): MediaRow?

    /** Bounded census of one parent dir: page-image count plus visible-subdir presence. */
    fun statsFor(dir: File): FolderStats
}

/**
 * Platform [MediaRowSource] over `MediaStore.Files`.
 *
 * TODO(device): row projection (_DATA/DISPLAY_NAME by row ID) is verified by shape only —
 * Robolectric's MediaStore shadow is too thin for cursor assertions, so confirm the
 * projection and the delete-visibility behaviour on a real device before release.
 */
class PlatformMediaRowSource(
    private val contentResolver: ContentResolver,
) : MediaRowSource {

    override fun rowFor(id: Long): MediaRow? {
        val uri = ContentUris.withAppendedId(MediaStoreObserver.FILES_EXTERNAL, id)
        val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns._ID} = ?"
        val path = contentResolver.query(uri, projection, selection, arrayOf(id.toString()), null)?.use { cursor ->
            // A missing row (deleted file) yields no path; the observer maps that to RescanRequested.
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf { it.isNotEmpty() }
                    ?: cursor.getString(1)?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
        return path?.let { MediaRow(id, it) }
    }

    override fun statsFor(dir: File): FolderStats {
        // Shared storage is real files, so a bounded readdir beats a second MediaStore query.
        val children = dir.listFiles()?.toList().orEmpty()
        var images = 0
        var hasSubdir = false
        for (child in children) {
            if (child.isDirectory) {
                if (!EntryFilter.isJunkDirectory(child.name)) hasSubdir = true
            } else if (EntryFilter.isPage(child.name)) {
                images++
            }
        }
        return FolderStats(images, hasSubdir)
    }
}
