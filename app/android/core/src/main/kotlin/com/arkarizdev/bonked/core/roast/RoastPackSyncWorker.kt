package com.arkarizdev.bonked.core.roast

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arkarizdev.bonked.core.BuildConfig
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * T-207 / tech plan §7: "daily WorkManager job: check → download when
 * newer → verify hash → atomic pointer swap." Structural mirror of
 * WatchdogWorker/AnalyticsFlushWorker — same KEEP-policy scheduling so
 * repeat schedule() calls (every startWatcher()) never stack duplicate
 * periodic work.
 */
class RoastPackSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val UNIQUE_WORK_NAME = "bonked_roast_pack_sync"
        private const val UNIQUE_ONCE_WORK_NAME = "bonked_roast_pack_sync_once"
        private const val INTERVAL_HOURS = 24L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RoastPackSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * An immediate one-time check alongside the daily schedule above,
         * so a fresh pack doesn't sit unsynced for up to 24h after the
         * watcher (re)starts — also what makes this testable locally
         * without adb job-namespace fights: run the app, finish
         * onboarding (or let the watchdog restart the service), and this
         * fires within seconds. ExistingWorkPolicy.KEEP: startWatcher()
         * can call this many times in a session (every watchdog self-heal
         * restart included) without ever stacking duplicate one-time work.
         */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<RoastPackSyncWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_ONCE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        val store = RoastPackStore(File(applicationContext.filesDir, "packs"))
        val gateway = RoastPackFetchGateway(BuildConfig.ROAST_PACK_MANIFEST_URL, OkHttpClient(), store)
        gateway.syncIfNeeded()
        return Result.success() // tomorrow's tick is the retry, never Result.retry() — same reasoning as AnalyticsFlushWorker
    }
}
