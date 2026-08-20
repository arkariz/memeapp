package com.arkarizdev.grudge

import com.arkarizdev.grudge.core.watcher.WatcherCore
import com.arkarizdev.grudge.core.watcher.WatcherService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        WatcherHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, WatcherApiHandler(this))
    }
}

/** Delegates every Pigeon call straight to the native core — no logic lives here. */
private class WatcherApiHandler(private val activity: MainActivity) : WatcherHostApi {
    // getStatus does real Room I/O (T-103) and must not block the main
    // thread, hence @async on the Pigeon side and a scope here to bridge
    // the callback-based generated interface to WatcherCore's suspend fun.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun getStatus(callback: (Result<WatcherStatusDto>) -> Unit) {
        scope.launch {
            val status = WatcherCore.status(activity)
            callback(
                Result.success(
                    WatcherStatusDto(
                        isRunning = status.isRunning,
                        heartbeatAgeMs = status.heartbeatAgeMs,
                        hasUsageAccess = status.hasUsageAccess,
                        hasOverlayPermission = status.hasOverlayPermission,
                        activeSessionCount = status.activeSessionCount.toLong(),
                    )
                )
            )
        }
    }

    override fun startWatcher() {
        WatcherCore.startWatcher(activity)
    }

    override fun debugGrant(pkg: String, minutes: Long, intentText: String?) {
        WatcherService.instance?.debugGrant(pkg, minutes.toInt(), intentText)
    }
}
