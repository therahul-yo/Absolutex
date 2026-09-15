package com.absolutex.core.data

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AbsolutexDatabase =
        // Pre-1.0 acceptable: a schema bump resets reading progress rather than crashing
        // on launch. Replace with an explicit Migration before 1.0 — progress is user data.
        Room.databaseBuilder(context, AbsolutexDatabase::class.java, "absolutex.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun progressDao(db: AbsolutexDatabase): ProgressDao = db.progressDao()

    /**
     * Missing until the library screen became the first thing to inject it. The table and DAO
     * shipped, the repository shipped, and nothing could obtain one through Hilt — which only
     * validates bindings something actually requests, so it compiled cleanly the whole time.
     */
    @Provides
    fun libraryDao(db: AbsolutexDatabase): LibraryDao = db.libraryDao()

    /** Total device RAM, the input to the cache ceiling (see MemoryBudget). */
    @Provides
    @Singleton
    @TotalRamBytes
    fun totalRamBytes(@ApplicationContext context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TotalRamBytes
