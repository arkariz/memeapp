package com.arkarizdev.grudge.core.watcher

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.arkarizdev.grudge.core.data.GrudgeDatabase

/**
 * Entry point the app module's Pigeon handler delegates to. Kept as a plain
 * object with no Flutter/Pigeon imports so the core module never depends on
 * the Flutter engine (tech plan §2 — core must run with the engine dead).
 *
 * status() is suspend: it now reads Room (heartbeat + active session
 * count), and Room forbids main-thread queries by design. The Pigeon
 * getStatus call is marked @async precisely so this can be a real suspend
 * function instead of reaching for allowMainThreadQueries() as a shortcut.
 */
object WatcherCore {
    /**
     * Heartbeat freshness beyond which the service is considered dead, not
     * just between poll ticks. A few poll intervals' worth of slack.
     */
    private const val STALE_HEARTBEAT_MS = 5_000L

    suspend fun status(context: Context): WatcherStatus {
        val db = GrudgeDatabase.get(context)
        val heartbeat = HeartbeatStore(db.heartbeatDao())
        val lastTick = heartbeat.lastTickAt()
        val heartbeatAgeMs = lastTick?.let { System.currentTimeMillis() - it }
        val isRunning = heartbeatAgeMs != null && heartbeatAgeMs < STALE_HEARTBEAT_MS
        return WatcherStatus(
            isRunning = isRunning,
            heartbeatAgeMs = heartbeatAgeMs,
            hasUsageAccess = hasUsageAccess(context),
            hasOverlayPermission = Settings.canDrawOverlays(context),
            // Real now (T-103): reads the same Room DB the running service
            // writes to, so this works cross-process, not just same-process.
            activeSessionCount = db.sessionDao().activeCount(),
        )
    }

    /** Starts the foreground service. No-op call site should check permissions first. */
    fun startWatcher(context: Context) {
        context.startForegroundService(Intent(context, WatcherService::class.java))
    }

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }
}
