import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/src/pigeon/watcher_api.g.dart',
    kotlinOut:
        'android/app/src/main/kotlin/com/arkarizdev/grudge/WatcherApi.g.kt',
    kotlinOptions: KotlinOptions(package: 'com.arkarizdev.grudge'),
    dartPackageName: 'grudge',
  ),
)
/// Status snapshot for the watcher service. Mirrors
/// com.arkarizdev.grudge.core.watcher.WatcherStatus — kept as plain DTOs per tech
/// plan §2 ("Pigeon typed channels — DTOs only, no SQL").
class WatcherStatusDto {
  WatcherStatusDto({
    required this.isRunning,
    this.heartbeatAgeMs,
    required this.hasUsageAccess,
    required this.hasOverlayPermission,
    required this.activeSessionCount,
  });

  bool isRunning;
  int? heartbeatAgeMs;
  bool hasUsageAccess;
  bool hasOverlayPermission;
  int activeSessionCount;
}

/// One launchable app, for the T-109 app-picker step. Sourced via a
/// launcher-intent `<queries>` declaration (PackageManager.queryIntentActivities
/// against ACTION_MAIN/CATEGORY_LAUNCHER) — never QUERY_ALL_PACKAGES, per
/// PRD P0-1 and the Play Console declaration in T-003. No icon bitmap: the
/// brutalist visual language (flat ink/paper/yellow, no photorealism) uses
/// a text-initial avatar instead, so there's no need to round-trip
/// per-density launcher icons through Pigeon.
class AppInfoDto {
  AppInfoDto({required this.pkg, required this.label});

  String pkg;
  String label;
}

/// One row of the watched_app table, as edited by the T-109 app picker.
class WatchedAppConfigDto {
  WatchedAppConfigDto({required this.pkg, required this.label, required this.budgetMin, required this.enabled});

  String pkg;
  String label;
  int budgetMin;
  bool enabled;
}

/// Snapshot of every special-access grant T-109's onboarding walks through,
/// beyond the two already in WatcherStatusDto (usage access, overlay).
class PermissionSnapshotDto {
  PermissionSnapshotDto({required this.hasNotificationPermission, required this.isIgnoringBatteryOptimizations});

  bool hasNotificationPermission;
  bool isIgnoringBatteryOptimizations;
}

/// Dart -> Kotlin: queries/commands the app module implements against
/// WatcherCore. startWatcher/debugGrant are dev/test affordances (T-102/
/// T-103) — the real product starts the service from onboarding (T-109)
/// and grants come from the intent-capture overlay (T-104), not a button.
@HostApi()
abstract class WatcherHostApi {
  /// @async because it now does real Room I/O (T-103) — Room forbids
  /// main-thread queries, and Pigeon host calls land on the main thread
  /// unless the method is marked async.
  @async
  WatcherStatusDto getStatus();

  void startWatcher();

  /// Dev/test-only: manually trigger a grant to verify "grant-reload-on-
  /// restart" without T-104's intent overlay existing yet.
  void debugGrant(String pkg, int minutes, String? intentText);

  /// T-109: the app picker's data source — every launchable app on-device,
  /// via the launcher-intent `<queries>` declaration.
  @async
  List<AppInfoDto> getLaunchableApps();

  /// T-109: current watched_app rows (empty on first run), so the app
  /// picker can pre-select apps if onboarding is re-entered (revocation
  /// recovery) rather than always starting from a blank slate.
  @async
  List<WatchedAppConfigDto> getWatchedApps();

  /// T-109: replaces the entire watched_app table with the picker's final
  /// selection + per-app budgets. Full-replace, not incremental — the
  /// picker screen always shows and edits the complete set.
  @async
  void saveWatchedApps(List<WatchedAppConfigDto> apps);

  /// T-109: the two special-access grants not already covered by
  /// WatcherStatusDto (usage access, overlay permission).
  @async
  PermissionSnapshotDto getPermissionSnapshot();

  void openUsageAccessSettings();

  void openOverlayPermissionSettings();

  /// Fires the in-app runtime permission dialog (POST_NOTIFICATIONS,
  /// API 33+; a no-op grant on older OSes). Result arrives asynchronously
  /// via WatcherFlutterApi.onNotificationPermissionResult, not a return
  /// value here, since Android's permission callback is itself async.
  void requestNotificationPermission();

  /// Launches the system's ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
  /// dialog directly (not a Settings page detour) with an honest
  /// explanation shown beforehand in-app, per PRD P0-1.
  void requestBatteryExemption();

  bool isOnboardingComplete();

  void setOnboardingComplete();
}

/// Kotlin -> Dart: the one result Android can only deliver via callback,
/// since ActivityCompat.requestPermissions itself is async on the platform
/// side (onRequestPermissionsResult, not a return value).
@FlutterApi()
abstract class WatcherFlutterApi {
  void onNotificationPermissionResult(bool granted);
}
