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
        Room.databaseBuilder(context, AbsolutexDatabase::class.java, "absolutex.db").build()

    @Provides
    fun progressDao(db: AbsolutexDatabase): ProgressDao = db.progressDao()

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
