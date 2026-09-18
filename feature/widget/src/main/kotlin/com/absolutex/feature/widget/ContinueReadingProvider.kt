package com.absolutex.feature.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receiver half; GlanceAppWidgetReceiver already refreshes on the system onUpdate broadcast. */
class ContinueReadingProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueReadingWidget()

    // Glance 1.2.0 declares the Intent non-null; unknown actions fall through to Glance's own handling.
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetIntents.ACTION_REFRESH) {
            refreshAll(context)
            return
        }
        super.onReceive(context, intent)
    }

    private fun refreshAll(context: Context) {
        // goAsync keeps the broadcast alive across the suspending Glance update.
        val done = goAsync()
        updates.launch {
            runCatching { updateAllWidgets(context) }
            done.finish()
        }
    }

    /** Pushes the latest models to every placed widget; a no-op when none is placed. */
    internal suspend fun updateAllWidgets(context: Context) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(ContinueReadingWidget::class.java)
        ids.forEach { glanceAppWidget.update(context, it) }
    }

    private companion object {
        // Independent of any caller scope: a widget refresh must survive its trigger going away.
        val updates = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
