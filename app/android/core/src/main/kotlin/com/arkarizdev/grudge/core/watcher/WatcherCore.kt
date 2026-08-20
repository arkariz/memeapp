package com.arkarizdev.grudge.core.watcher

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.arkarizdev.grudge.core.data.GrudgeDatabase
import com.arkarizdev.grudge.core.data.WatchedAppEntity

data class AppInfo(val pkg: String, val label: String)

data class WatchedAppConfig(val pkg: String, val label: String, val budgetMin: Int, val enabled: Boolean)

data class PermissionSnapshot(val hasNotificationPermission: Boolean, val isIgnoringBatteryOptimizations: Boolean)

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

    /**
     * T-109 app-picker data source: every launchable app on-device via a
     * launcher-intent query (ACTION_MAIN/CATEGORY_LAUNCHER) — never
     * QUERY_ALL_PACKAGES, matching the `<queries>` declaration in
     * AndroidManifest and the Play Console usage-access declaration (T-003)
     * that promises exactly this scope. Excludes this app itself — Grudge
     * can't watch Grudge.
     */
    fun getLaunchableApps(context: Context): List<AppInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }
        return resolved
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { pkg -> AppInfo(pkg, resolveAppLabel(context, pkg)) }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun getWatchedApps(context: Context): List<WatchedAppConfig> {
        val rows = GrudgeDatabase.get(context).watchedAppDao().all()
        return rows.map { WatchedAppConfig(it.pkg, resolveAppLabel(context, it.pkg), it.budgetMin, it.enabled) }
    }

    suspend fun saveWatchedApps(context: Context, apps: List<WatchedAppConfig>) {
        val now = System.currentTimeMillis()
        GrudgeDatabase.get(context).watchedAppDao().replaceAll(
            apps.map { WatchedAppEntity(pkg = it.pkg, budgetMin = it.budgetMin, enabled = it.enabled, addedAt = now) }
        )
    }

    fun getPermissionSnapshot(context: Context): PermissionSnapshot {
        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // permission didn't exist pre-Tiramisu; notifications were unconditionally allowed
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return PermissionSnapshot(
            hasNotificationPermission = hasNotificationPermission,
            isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    fun openUsageAccessSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    fun openOverlayPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    }

    /**
     * Must be an Activity, not just a Context — ActivityCompat.requestPermissions
     * delivers its result via Activity.onRequestPermissionsResult, which only
     * an Activity can receive. MainActivity relays that callback to Dart via
     * WatcherFlutterApi.onNotificationPermissionResult (see MainActivity.kt).
     */
    fun requestNotificationPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return // nothing to request pre-33
        ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), requestCode)
    }

    /**
     * The direct system dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
     * not a Settings-page detour — PRD P0-1 asks for an honest in-app
     * explanation BEFORE this fires, which the onboarding screen shows;
     * this call is only reached after the user taps through that screen.
     */
    fun requestBatteryExemption(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
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
