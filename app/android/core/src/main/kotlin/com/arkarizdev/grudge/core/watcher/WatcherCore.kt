package com.arkarizdev.grudge.core.watcher

import android.content.Context

/**
 * Entry point the app module's Pigeon handler delegates to. Kept as a plain
 * object with no Flutter/Pigeon imports so the core module never depends on
 * the Flutter engine (tech plan §2 — core must run with the engine dead).
 *
 * Stub for T-101 scaffold. Heartbeat persistence + real service lifecycle
 * are T-102/T-103.
 */
object WatcherCore {
    fun status(context: Context): WatcherStatus {
        // TODO(T-110): read the real heartbeat row once Room lands (T-103).
        return WatcherStatus(isRunning = false, heartbeatAgeMs = null)
    }
}
