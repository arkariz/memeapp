package com.arkarizdev.grudge

import com.arkarizdev.grudge.core.watcher.WatcherCore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        WatcherHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, WatcherApiHandler(this))
    }
}

/** Delegates every Pigeon call straight to the native core — no logic lives here. */
private class WatcherApiHandler(private val activity: MainActivity) : WatcherHostApi {
    override fun getStatus(): WatcherStatusDto {
        val status = WatcherCore.status(activity)
        return WatcherStatusDto(
            isRunning = status.isRunning,
            heartbeatAgeMs = status.heartbeatAgeMs,
            hasUsageAccess = status.hasUsageAccess,
            hasOverlayPermission = status.hasOverlayPermission,
            activeSessionCount = status.activeSessionCount.toLong(),
        )
    }

    override fun startWatcher() {
        WatcherCore.startWatcher(activity)
    }
}
