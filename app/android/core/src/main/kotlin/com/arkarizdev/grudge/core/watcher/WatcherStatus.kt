package com.arkarizdev.grudge.core.watcher

/** Status snapshot exposed to the Flutter side via Pigeon. */
data class WatcherStatus(
    val isRunning: Boolean,
    val heartbeatAgeMs: Long?,
    val hasUsageAccess: Boolean,
    val hasOverlayPermission: Boolean,
    val activeSessionCount: Int,
)
