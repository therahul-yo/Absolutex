package com.absolutex.feature.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/** All widget intents in one place so the deep-link path is testable without Glance running. */
object WidgetIntents {
    /** Explicit refresh; the reader calls requestRefresh after each progress write (lead TODO). */
    const val ACTION_REFRESH = "com.absolutex.feature.widget.action.REFRESH"

    /**
     * Library books resolve to file:// paths (MainActivity's VIEW filter handles file and
     * content schemes); SAF books have no library row, so their tap opens the app rather
     * than a Uri parsed from a bare BookIdentity id — Uri.parse on "Name.cbz:104857600"
     * fabricates a scheme nothing resolves (lead review, item 2).
     */
    fun tapUri(libraryPath: String?): Uri? = libraryPath?.let { Uri.fromFile(File(it)) }

    /**
     * Explicit to our own package: an implicit ACTION_VIEW would resolve to any installed
     * reader (review item 3), and at targetSdk 36 an implicit intent carrying a file:// Uri
     * throws FileUriExposedException on PendingIntent (review item 1). With the package set,
     * prepareToLeaveProcess sees no package leave and the Uri is permitted.
     */
    fun viewIntent(bookUri: Uri, context: Context): Intent =
        Intent(Intent.ACTION_VIEW, bookUri).setPackage(context.packageName)

    /** Single tap target for the Glance callback: a stored Uri opens the book, null opens the app. */
    fun tapIntent(context: Context, bookUri: String?): Intent {
        val intent = if (bookUri != null) {
            viewIntent(Uri.parse(bookUri), context)
        } else {
            emptyStateIntent(context)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Empty state parks on the launcher activity (the SAF picker), never on a dead end. */
    fun emptyStateIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            // Unit-test manifests declare no launcher entry; production always has MainActivity.
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)

    /** Explicit broadcast to our own receiver; updatePeriodMillis stays 0 so this is the only refresh path. */
    fun requestRefresh(context: Context) {
        context.sendBroadcast(Intent(ACTION_REFRESH).setClass(context, ContinueReadingProvider::class.java))
    }
}
