package com.absolutex

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a file picked with the "Open file" button. It goes through the same sheet a library row
 * does, and a file whose grant holds is also remembered for resume and recorded in the library so
 * it shows up in Recent. A file whose grant does not hold still opens, for this session only.
 */
internal fun openPickedFile(context: Context, vm: ShellViewModel, picked: Uri, open: (Uri) -> Unit) {
    if (keepGrant(context, picked)) {
        vm.rememberBook(picked.toString())
        vm.recordPickedFile(picked)
    }
    open(picked)
}

/**
 * Takes a persistable grant on a picked document and reports whether it actually held.
 *
 * OpenDocument offers a persistable grant, but not every provider honours it — take() then throws
 * SecurityException. Attempt, then VERIFY against persistedUriPermissions: only a verified Uri
 * survives process death, and only a verified Uri is remembered for §5.2 resume.
 */
private fun keepGrant(context: Context, picked: Uri): Boolean {
    runCatching {
        context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.onFailure {
        // The class only: a SecurityException's message names the Uri.
        android.util.Log.w("Shell", "persistable grant refused: ${it.javaClass.name}")
    }
    val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == picked }
    if (!persisted) android.util.Log.w("Shell", "grant not persisted; opening for this session only")
    return persisted
}
