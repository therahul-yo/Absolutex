package com.absolutex

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.absolutex.remote.sync.SyncController
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
 * plain onActivityStopped would push on every navigation.
 *
 * NOTE: the legacy sync-server migration (SyncServerMigration, TODO(agent3)) is NOT
 * called here yet — the one-liner needs Agent03's clearance first. Flagged to the lead.
 */
@HiltAndroidApp
class AbsolutexApp : Application() {

    @Inject lateinit var sync: SyncController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var visibleActivities = 0

    override fun onCreate() {
        super.onCreate()
        scope.launch { sync.onAppStart() }
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivities++
            }

            override fun onActivityStopped(activity: Activity) {
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
