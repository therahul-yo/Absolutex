package com.absolutex.feature.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Provider behaviour with a fake source: composition stays untested without the Glance runner (see PR). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderTest {

    private class FakeSource(private val models: List<WidgetModel>) : ContinueReadingSource {
        override suspend fun inProgress(limit: Int): List<WidgetModel> = models.take(limit.coerceAtLeast(0))
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun tearDown() {
        WidgetSources.override = null
    }

    @Test fun `empty source loads zero rows for the empty state`() = runTest {
        assertEquals(emptyList<Pair<WidgetModel, String?>>(), loadRows(FakeSource(emptyList())))
    }

    @Test fun `fake source rows load with null tap paths`() = runTest {
        val models = listOf(
            WidgetModel.from("a.cbz:1", "Alpha", 0, 10, null),
            WidgetModel.from("b.cbz:2", "Beta", 4, 10, null),
        )
        val rows = loadRows(FakeSource(models))
        assertEquals(models, rows.map { it.first })
        rows.forEach { assertNull(it.second) }
    }

    @Test fun `resolving through the override seam uses the fake`() = runTest {
        val models = listOf(WidgetModel.from("a.cbz:1", "Alpha", 0, 10, null))
        WidgetSources.override = FakeSource(models)
        assertEquals(models, WidgetSources.resolve(context).inProgress(WIDGET_MAX_ITEMS))
    }

    @Test fun `updating with no placed widget completes`() = runTest {
        WidgetSources.override = FakeSource(emptyList())
        ContinueReadingProvider().updateAllWidgets(context)
    }

    @Test fun `provider info disables polling`() {
        val parser = context.resources.getXml(R.xml.continue_reading_info)
        var type = parser.eventType
        var period: String? = null
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG && parser.name == "appwidget-provider") {
                period = parser.getAttributeValue(
                    "http://schemas.android.com/apk/res/android",
                    "updatePeriodMillis",
                )
            }
            type = parser.next()
        }
        assertEquals("0", period)
    }
}
