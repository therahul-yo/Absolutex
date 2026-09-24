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
        // No destructive fallback: reading progress, favourites and bookmarks are user data.
        // Every schema step has an AutoMigration (exported schemas, checked in CI), so the
        // fallback only ever fired on a DOWNGRADE — an older build installed over a newer one —
        // and it silently erased a reader's every position. Now that case fails loudly on launch
        // instead, and installing the newer build again opens the data intact.
        Room.databaseBuilder(context, AbsolutexDatabase::class.java, "absolutex.db").build()

    @Provides
    fun progressDao(db: AbsolutexDatabase): ProgressDao = db.progressDao()

    /**
     * Missing until the library screen became the first thing to inject it. The table and DAO
     * shipped, the repository shipped, and nothing could obtain one through Hilt — which only
     * validates bindings something actually requests, so it compiled cleanly the whole time.
     */
    @Provides
    fun libraryDao(db: AbsolutexDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun bookFactsDao(db: AbsolutexDatabase): BookFactsDao = db.bookFactsDao()

    /** Reading history's store. The table and ReadingHistory shipped; nothing could inject them. */
    @Provides
    fun pageViewDao(db: AbsolutexDatabase): PageViewDao = db.pageViewDao()

    @Provides
    fun bookmarkDao(db: AbsolutexDatabase): BookmarkDao = db.bookmarkDao()

    @Provides
    fun bookPrefsDao(db: AbsolutexDatabase): BookPrefsDao = db.bookPrefsDao()

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
