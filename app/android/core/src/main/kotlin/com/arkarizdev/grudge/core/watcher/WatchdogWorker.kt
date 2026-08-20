package com.arkarizdev.grudge.core.watcher

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
 * OEM-breakdown uptime telemetry (PRD risk #2, T-302's dependency) is
 * deliberately NOT written here — that needs the analytics pipeline
 * (T-203), which doesn't exist yet. This worker only self-heals.
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
        if (!status.isRunning && status.hasUsageAccess && status.hasOverlayPermission) {
            Log.i(TAG, "watcher dead but permissions intact — restarting")
            WatcherCore.startWatcher(applicationContext)
        }
        return Result.success()
    }
}
