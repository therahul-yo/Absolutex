package com.absolutex.feature.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val uri = WidgetIntents.tapUri("Batman 001.cbz:100", "/sd/Comics/Batman 001.cbz")
        assertEquals("file", uri?.scheme)
        assertEquals("/sd/Comics/Batman 001.cbz", uri?.path)
    }

    @Test fun `deep link without a library path is null, never a fabricated uri`() {
        // Regression (lead review item 2): Uri.parse("Name.cbz:104857600") invented a scheme
        // nothing resolves, so SAF books silently did nothing on tap.
        assertNull(WidgetIntents.tapUri("Batman 001.cbz:100", null))
    }

    @Test fun `tap intent is ACTION_VIEW with the book uri`() {
        val uri = WidgetIntents.tapUri("content://com.example/tree/7", null) ?: return
        val intent = WidgetIntents.viewIntent(uri, context)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
    }

    @Test fun `view intent is explicit to our own package`() {
        // Regression (lead review items 1 and 3): an implicit ACTION_VIEW could resolve to
        // another reader and throws FileUriExposedException for file:// at targetSdk 36.
        val intent = WidgetIntents.viewIntent(
            WidgetIntents.tapUri("a.cbz:1", "/sd/a.cbz")!!,
            context,
        )
        assertEquals(context.packageName, intent.`package`)
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
