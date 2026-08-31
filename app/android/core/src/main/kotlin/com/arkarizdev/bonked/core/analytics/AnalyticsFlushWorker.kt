package com.arkarizdev.bonked.core.analytics

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * T-203: periodic flush of analytics_evt to PostHog. Structural mirror of
 * WatchdogWorker — 15 minutes is WorkManager's platform floor, which is
 * invisible at the granularity this data is actually used for (a weekly
 * cohort tripwire, §6), not a real-time pipe. Independent of WatcherService's
 * lifecycle on purpose: onboarding events happen before the watcher ever
 * starts, and the watcher can be dead for a while before WatchdogWorker
 * restarts it — this worker keeps flushing regardless.
 */
class AnalyticsFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "BonkedAnalyticsFlush"
        private const val UNIQUE_WORK_NAME = "bonked_analytics_flush"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnalyticsFlushWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        val ok = AnalyticsCore.buildGateway(applicationContext).flush()
        Log.i(TAG, "flush ok=$ok")
        return Result.success() // the next 15-min tick is the retry, never Result.retry()
    }
}
