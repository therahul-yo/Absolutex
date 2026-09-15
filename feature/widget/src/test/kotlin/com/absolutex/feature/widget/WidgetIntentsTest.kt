package com.absolutex.feature.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContextWrapper

/** Intent factories without Glance running; Robolectric supplies the PackageManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetIntentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `deep link prefers the library file path`() {
        val uri = WidgetIntents.deepLinkUri("Batman 001.cbz:100", "/sd/Comics/Batman 001.cbz")
        assertEquals("file", uri.scheme)
        assertEquals("/sd/Comics/Batman 001.cbz", uri.path)
    }

    @Test fun `deep link falls back to the stored book id`() {
        // SAF books without display metadata use the Uri string itself as the identity.
        val uri = WidgetIntents.deepLinkUri("content://com.example/tree/7", null)
        assertEquals("content://com.example/tree/7", uri.toString())
    }

    @Test fun `tap intent is ACTION_VIEW with the book uri`() {
        val uri = WidgetIntents.deepLinkUri("content://com.example/tree/7", null)
        val intent = WidgetIntents.viewIntent(uri)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
    }

    @Test fun `empty state resolves inside our own package`() {
        val intent = WidgetIntents.emptyStateIntent(context)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(context.packageName, intent.`package`)
    }

    @Test fun `tap intent with a uri opens the book for viewing`() {
        val intent = WidgetIntents.tapIntent(context, "content://com.example/tree/7")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content://com.example/tree/7", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `tap intent without a uri opens the app`() {
        val intent = WidgetIntents.tapIntent(context, null)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `refresh request is an explicit broadcast to the provider`() {
        WidgetIntents.requestRefresh(context)
        // Broadcast tracking moved off ShadowApplication; ContextWrapper carries it in this Robolectric.
        val shadow = Shadows.shadowOf(context as android.app.Application) as ShadowContextWrapper
        val refresh = shadow.broadcastIntents.single { it.action == WidgetIntents.ACTION_REFRESH }
        assertEquals(ContinueReadingProvider::class.java.name, refresh.component?.className)
    }
}
