package com.arkarizdev.grudge.core.watcher

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * Entry point the app module's Pigeon handler delegates to. Kept as a plain
 * object with no Flutter/Pigeon imports so the core module never depends on
 * the Flutter engine (tech plan §2 — core must run with the engine dead).
 */
object WatcherCore {
    /**
     * Heartbeat freshness beyond which the service is considered dead, not
     * just between poll ticks. A few poll intervals' worth of slack.
     */
    private const val STALE_HEARTBEAT_MS = 5_000L

    fun status(context: Context): WatcherStatus {
        val heartbeat = HeartbeatStore(context)
        val lastTick = heartbeat.lastTickAt()
        val heartbeatAgeMs = lastTick?.let { System.currentTimeMillis() - it }
        val isRunning = heartbeatAgeMs != null && heartbeatAgeMs < STALE_HEARTBEAT_MS
        return WatcherStatus(
            isRunning = isRunning,
            heartbeatAgeMs = heartbeatAgeMs,
            hasUsageAccess = hasUsageAccess(context),
            hasOverlayPermission = Settings.canDrawOverlays(context),
            // TODO(T-101 follow-up): read this straight from the running
            // service once there's a real cross-process channel; for now
            // this is only accurate when called from the same process.
            activeSessionCount = 0,
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
