package com.absolutex.feature.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/** All widget intents in one place so the deep-link path is testable without Glance running. */
object WidgetIntents {
    /** Explicit refresh; the reader calls requestRefresh after each progress write (lead TODO). */
    const val ACTION_REFRESH = "com.absolutex.feature.widget.action.REFRESH"

    /** file:// paths match MainActivity's VIEW file filter; bare ids fall back to the stored Uri string. */
    fun deepLinkUri(bookId: String, libraryPath: String?): Uri =
        if (libraryPath != null) Uri.fromFile(File(libraryPath)) else Uri.parse(bookId)

    /** ACTION_VIEW is already handled by MainActivity's VIEW filter (app manifest) — no edit there. */
    fun viewIntent(bookUri: Uri): Intent = Intent(Intent.ACTION_VIEW, bookUri)

    /** Single tap target for the Glance callback: a stored Uri opens the book, null opens the app. */
    fun tapIntent(context: Context, bookUri: String?): Intent {
        val intent = if (bookUri != null) viewIntent(Uri.parse(bookUri)) else emptyStateIntent(context)
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
