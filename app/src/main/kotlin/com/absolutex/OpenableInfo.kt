package com.absolutex

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** What a provider says about a document it handed us: its file name and, if it knows, its size. */
internal data class OpenableInfo(val name: String, val sizeBytes: Long?)

/** The name and size of [uri], or null when the provider will not say. Blocking: call off the main thread. */
internal fun Context.openableInfo(uri: Uri): OpenableInfo? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            OpenableInfo(name, if (c.isNull(1)) null else c.getLong(1))
        }
}.getOrNull()
