package com.arkarizdev.grudge.core.watcher

/**
 * Minimal status snapshot exposed to the Flutter side via Pigeon.
 * Real fields (heartbeat age, active session count) land in T-102/T-110 —
 * this scaffold only proves the core module -> Pigeon -> Dart pipeline works.
 */
data class WatcherStatus(
    val isRunning: Boolean,
    val heartbeatAgeMs: Long?,
)
