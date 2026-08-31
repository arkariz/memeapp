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

/// T-201 home screen: one watched app's today-so-far usage vs its budget.
class AppUsageDto {
  AppUsageDto({required this.pkg, required this.label, required this.usedMin, required this.budgetMin});

  String pkg;
  String label;
  int usedMin;
  int budgetMin;
}

/// T-201 home screen (Figma 05): everything the home screen needs in one
/// call — watch status (reusing the same fields as WatcherStatusDto rather
/// than nesting it, since Pigeon DTOs don't compose well), the streak, and
/// each enabled watched app's today-so-far usage.
class HomeSnapshotDto {
  HomeSnapshotDto({
    required this.isRunning,
    required this.hasUsageAccess,
    required this.hasOverlayPermission,
    this.watcherStartedAtMs,
    required this.streakCurrent,
    required this.streakBest,
    required this.apps,
  });

  bool isRunning;
  bool hasUsageAccess;
  bool hasOverlayPermission;
  int? watcherStartedAtMs;
  int streakCurrent;
  int streakBest;
  List<AppUsageDto> apps;
}

/// T-202: one success-side share-card candidate. [kind] is "BEATEN" or
/// "STREAK_MILESTONE" (see WatcherCore.CardSnapshot's doc comment for why
/// this is a plain string, not a Pigeon enum). [referenceId] is whatever
/// [WatcherHostApi.acknowledgeCard] needs to mark this exact event as
/// already celebrated — a session id for BEATEN, the streak count itself
/// for STREAK_MILESTONE.
class CardDto {
  CardDto({
    required this.kind,
    required this.pkg,
    required this.appLabel,
    required this.grantedMin,
    required this.takenMin,
    required this.streakCount,
    required this.dateIso,
    required this.referenceId,
  });

  String kind;
  String pkg;
  String appLabel;
  int grantedMin;
  int takenMin;
  int streakCount;
  String dateIso;
  int referenceId;
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

  /// T-201: the home screen's one data call — streak + per-app usage bars.
  @async
  HomeSnapshotDto getHomeSnapshot();

  /// T-202: the next success-side card to show, or null if nothing new
  /// beat its estimate / hit a streak milestone since the last one shown.
  @async
  CardDto? getPendingCard();

  /// T-202: marks [kind]/[referenceId] as already celebrated so
  /// getPendingCard never returns it again. Called once the card screen
  /// is dismissed, shared or not.
  void acknowledgeCard(String kind, int referenceId);

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

  /// T-203: generic analytics write path for Dart-originated events
  /// (onboarding_step, card_generated, card_shared) — mirrors
  /// analytics_evt's own name/propsJson shape. propsJson must match the
  /// fixed per-event allowlist enforced Kotlin-side in
  /// AnalyticsCore.logEventFromBridge (no-PII audit) — unlisted keys are
  /// dropped, not sent, if this ever drifts out of sync.
  void logAnalyticsEvent(String name, String propsJson);
}

/// Kotlin -> Dart: the one result Android can only deliver via callback,
/// since ActivityCompat.requestPermissions itself is async on the platform
/// side (onRequestPermissionsResult, not a return value).
@FlutterApi()
abstract class WatcherFlutterApi {
  void onNotificationPermissionResult(bool granted);
}
