package com.absolutex.feature.widget

/** Homescreen data contract; Room results are capped at WIDGET_MAX_ITEMS so one update stays cheap. */
const val WIDGET_MAX_ITEMS = 5

interface ContinueReadingSource {
    suspend fun inProgress(limit: Int): List<WidgetModel>
}
