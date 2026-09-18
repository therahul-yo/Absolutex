package com.absolutex

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.absolutex.remote.sync.SyncController
import com.absolutex.remote.sync.migrateLegacySyncServers
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application shell: fires the sync controller's app-start pull and the app-backgrounded
 * push, per the TODO(library) contract on [SyncController].
 *
 * Background detection is a manual visible-activity count, not lifecycle-process: that
 * artifact is deliberately not a dependency. The count reaches zero only when no activity
 * is visible — navigating library to reader stops one activity but starts another, so a
 * plain onActivityStopped would push on every navigation. Rotation is not a background
 * transition either: the stopping instance reports isChangingConfigurations, so its stop
 * is skipped and the count never dips (the recreating instance's start rebalances it).
 *
 * Legacy sync-server migration runs here too (one-shot, idempotent): old sync records
 * become unified servers on first launch after update.
 */
@HiltAndroidApp
class AbsolutexApp : Application() {

    @Inject lateinit var sync: SyncController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var visibleActivities = 0

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            migrateLegacySyncServers(this@AbsolutexApp)
            sync.onAppStart()
        }
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                // Rotation (or any config change): the old instance stops while its
                // replacement starts — not a background transition, so don't count it.
                if (activity.isChangingConfigurations) return
                if (--visibleActivities == 0) {
                    scope.launch { sync.onAppBackgrounded() }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
