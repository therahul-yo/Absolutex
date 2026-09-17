package com.absolutex.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.text.Text

private const val PERCENT = 100

/** Glance widget: no hand-written layout XML, so classic RemoteViews was never an option (see PR). */
class ContinueReadingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = loadRows(WidgetSources.resolve(context))
        provideContent { Content(rows) }
    }
}

/** Assembles models plus tap paths; testable without Glance running by passing a fake source. */
internal suspend fun loadRows(source: ContinueReadingSource): List<Pair<WidgetModel, String?>> =
    // A widget update must never surface an exception: on failure the empty state shows, not last week's book.
    runCatching {
        val room = source as? RoomContinueReadingSource
        // One library pass per row for the tap path; a single row today, still bounded by WIDGET_MAX_ITEMS.
        source.inProgress(WIDGET_MAX_ITEMS).map { it to room?.libraryPathFor(it.bookId) }
    }.getOrDefault(emptyList())

/** Internal so the Glance composition test can render it directly (lead review, item 4). */
@Composable
internal fun Content(rows: List<Pair<WidgetModel, String?>>) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize().appWidgetBackground()) {
        if (rows.isEmpty()) {
            Text(text = context.getString(R.string.widget_empty_title))
            Button(
                text = context.getString(R.string.widget_empty_cta),
                onClick = actionStartActivity(WidgetIntents.tapIntent(context, null)),
            )
        } else {
            Text(text = context.getString(R.string.widget_title))
            rows.forEach { (model, path) ->
                // No library path -> no fabricated Uri (review item 2): the tap opens the app.
                val uri = WidgetIntents.tapUri(path)?.toString()
                Column(
                    modifier = GlanceModifier.fillMaxWidth()
                        .clickable(actionStartActivity(WidgetIntents.tapIntent(context, uri))),
                ) {
                    Text(text = model.title, maxLines = 1)
                    Text(
                        text = context.getString(
                            R.string.widget_progress,
                            model.pageIndex + 1,
                            model.pageCount,
                            (model.progressFraction * PERCENT).toInt(),
                        ),
                    )
                    LinearProgressIndicator(progress = model.progressFraction)
                }
            }
        }
    }
}
