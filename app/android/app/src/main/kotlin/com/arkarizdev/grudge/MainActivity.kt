package com.arkarizdev.grudge

import com.arkarizdev.grudge.core.watcher.OnboardingPrefs
import com.arkarizdev.grudge.core.watcher.WatcherCore
import com.arkarizdev.grudge.core.watcher.WatcherService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity() {
    private val flutterApiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var flutterApi: WatcherFlutterApi? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        WatcherHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, WatcherApiHandler(this))
        flutterApi = WatcherFlutterApi(flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        flutterApiScope.launch {
            flutterApi?.onNotificationPermissionResult(granted) {}
        }
    }

    companion object {
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 4210
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

    override fun getLaunchableApps(callback: (Result<List<AppInfoDto>>) -> Unit) {
        callback(
            Result.success(
                WatcherCore.getLaunchableApps(activity).map { AppInfoDto(pkg = it.pkg, label = it.label) }
            )
        )
    }

    override fun getWatchedApps(callback: (Result<List<WatchedAppConfigDto>>) -> Unit) {
        scope.launch {
            val apps = WatcherCore.getWatchedApps(activity).map {
                WatchedAppConfigDto(pkg = it.pkg, label = it.label, budgetMin = it.budgetMin.toLong(), enabled = it.enabled)
            }
            callback(Result.success(apps))
        }
    }

    override fun saveWatchedApps(apps: List<WatchedAppConfigDto>, callback: (Result<Unit>) -> Unit) {
        scope.launch {
            WatcherCore.saveWatchedApps(
                activity,
                apps.map {
                    com.arkarizdev.grudge.core.watcher.WatchedAppConfig(
                        pkg = it.pkg,
                        label = it.label,
                        budgetMin = it.budgetMin.toInt(),
                        enabled = it.enabled,
                    )
                },
            )
            callback(Result.success(Unit))
        }
    }

    override fun getPermissionSnapshot(callback: (Result<PermissionSnapshotDto>) -> Unit) {
        val snapshot = WatcherCore.getPermissionSnapshot(activity)
        callback(
            Result.success(
                PermissionSnapshotDto(
                    hasNotificationPermission = snapshot.hasNotificationPermission,
                    isIgnoringBatteryOptimizations = snapshot.isIgnoringBatteryOptimizations,
                )
            )
        )
    }

    override fun openUsageAccessSettings() {
        WatcherCore.openUsageAccessSettings(activity)
    }

    override fun openOverlayPermissionSettings() {
        WatcherCore.openOverlayPermissionSettings(activity)
    }

    override fun requestNotificationPermission() {
        WatcherCore.requestNotificationPermission(activity, MainActivity.NOTIFICATION_PERMISSION_REQUEST_CODE)
    }

    override fun requestBatteryExemption() {
        WatcherCore.requestBatteryExemption(activity)
    }

    override fun isOnboardingComplete(): Boolean = OnboardingPrefs.isComplete(activity)

    override fun setOnboardingComplete() {
        OnboardingPrefs.setComplete(activity)
    }
}
