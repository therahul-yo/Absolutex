package com.absolutex.feature.widget

import android.content.Context
import com.absolutex.core.data.LibraryDao
import com.absolutex.core.data.ProgressDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Hilt bridge so the widget reuses :app's singleton DAOs instead of opening a second database. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun progressDao(): ProgressDao
    fun libraryDao(): LibraryDao
}

/** Test seam: production resolves Room DAOs from Hilt, tests install a fake through override. */
object WidgetSources {
    @Volatile var override: ContinueReadingSource? = null

    fun resolve(context: Context): ContinueReadingSource {
        override?.let { return it }
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        return RoomContinueReadingSource(entry.progressDao(), entry.libraryDao())
    }

    /** Room impl when that is what resolve returned, so callers can reach libraryPathFor. */
    fun asRoom(context: Context): RoomContinueReadingSource? = resolve(context) as? RoomContinueReadingSource
}
