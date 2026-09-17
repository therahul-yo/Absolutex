package com.absolutex.feature.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real Glance composition on the JVM (lead review item 4): the tap-intent
 * regressions shipped green because nothing exercised Content's composition paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetCompositionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `empty composition shows the empty state`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { Content(emptyList()) }
            onNode(hasText(context.getString(R.string.widget_empty_title))).assertExists()
            onNode(hasText(context.getString(R.string.widget_empty_cta)))
                .assertHasStartActivityClickAction(WidgetIntents.tapIntent(context, null))
        }
    }

    @Test fun `library row renders title, progress and a package-scoped tap action`() = runTest {
        val model = WidgetModel.from("Batman 001.cbz:100", "Absolute Batman", 3, 10, null)
        val rowIntent = WidgetIntents.tapIntent(
            context,
            WidgetIntents.tapUri("/sd/Comics/Batman 001.cbz")?.toString(),
        )
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { Content(listOf(model to "/sd/Comics/Batman 001.cbz")) }
            onNode(hasText(context.getString(R.string.widget_title))).assertExists()
            onNode(hasText("Absolute Batman")).assertExists()
            // widget_progress: page 4 of 10 = 40%.
            onNode(hasText(context.getString(R.string.widget_progress, 4, 10, 40))).assertExists()
            // The tap action must carry our package (regression: review items 1 and 3).
            onNode(hasStartActivityClickAction(rowIntent)).assertExists()
        }
    }
}
