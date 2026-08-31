package com.arkarizdev.grudge.core.watcher

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arkarizdev.grudge.core.analytics.AnalyticsCore
import java.util.concurrent.TimeUnit

/**
 * T-110: the periodic safety net for when the foreground service dies
 * without the user ever reopening the app — PRD P0-1's "the app never
 * silently pretends to work" only holds if something checks even while
 * the app is closed. WorkManager's minimum periodic interval is 15
 * minutes; this is a coarse net, not a fast responder, which is fine —
 * the fast path is the in-app red banner (WatchDownScreen) checked on
 * every app open/resume.
 *
 * Self-heals only the case it safely can: heartbeat stale but both
 * permissions still granted means the OS killed the service (battery
 * saver, OEM aggressiveness), not a revoked permission, so restarting it
 * is safe and correct. If a permission is missing, there's no UI a
 * background worker can show — that's the in-app recovery screen's job,
 * reached the next time the user opens the app.
 *
 * T-203: also writes the watch_down analytics event (PRD risk #2,
 * T-302's dependency) every tick the watcher is found down — not
 * deduped to one-shot-per-outage, since a longer outage is itself
 * useful signal for the OEM-breakdown tripwire, and the simplest
 * correct behavior is "log what's true right now."
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "GrudgeWatchdog"
        private const val UNIQUE_WORK_NAME = "grudge_watchdog"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        val status = WatcherCore.status(applicationContext)
        if (!status.isRunning) {
            val reason = when {
                !status.hasUsageAccess -> "usage_access_revoked"
                !status.hasOverlayPermission -> "overlay_revoked"
                else -> "service_dead"
            }
            AnalyticsCore.logEvent(
                applicationContext,
                "watch_down",
                mapOf(
                    "reason" to reason,
                    "oem" to Build.MANUFACTURER,
                    "uptime_s" to status.heartbeatAgeMs?.let { it / 1000 },
                ),
            )
        }
        if (!status.isRunning && status.hasUsageAccess && status.hasOverlayPermission) {
            Log.i(TAG, "watcher dead but permissions intact — restarting")
            WatcherCore.startWatcher(applicationContext)
        }
        return Result.success()
    }
}
