package com.arkarizdev.bonked.core.roast

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
        private const val INTERVAL_HOURS = 24L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RoastPackSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        val store = RoastPackStore(File(applicationContext.filesDir, "packs"))
        val gateway = RoastPackFetchGateway(BuildConfig.ROAST_PACK_MANIFEST_URL, OkHttpClient(), store)
        gateway.syncIfNeeded()
        return Result.success() // tomorrow's tick is the retry, never Result.retry() — same reasoning as AnalyticsFlushWorker
    }
}
