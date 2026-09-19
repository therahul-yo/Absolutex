package com.absolutex

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.absolutex.remote.sync.SyncController
import com.absolutex.remote.sync.migrateLegacySyncServers
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
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
 * transition either: the stopping instance reports isChangingConfigurations, so only the
 * event is suppressed while the count still decrements (the recreating instance's start
 * rebalances it) — otherwise the count drifts +1 per rotation and background sync dies.
 *
 * Legacy sync-server migration runs here too (one-shot, idempotent): old sync records
 * become unified servers on first launch after update. The migration fails closed on an
 * unparseable legacy document, so its exception is caught and logged at the call site —
 * a torn document must never take down launch — and the start pull still runs.
 */
@HiltAndroidApp
class AbsolutexApp : Application() {

    @Inject lateinit var sync: SyncController

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            android.util.Log.e("AbsolutexApp", "unhandled sync coroutine", e)
        },
    )
    private var visibleActivities = 0

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            runCatching { migrateLegacySyncServers(this@AbsolutexApp) }
                .onFailure { android.util.Log.e("AbsolutexApp", "legacy sync migration failed", it) }
            sync.onAppStart()
        }
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                // Always decrement: a config change still starts a replacement instance,
                // so skipping the decrement drifts +1 per rotation and background sync
                // dies for the rest of the process. Only the event is suppressed — a
                // rotation is not a background transition.
                visibleActivities--
                if (visibleActivities == 0 && !activity.isChangingConfigurations) {
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
