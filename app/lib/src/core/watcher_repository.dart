import '../pigeon/watcher_api.g.dart';

/// Single seam between UI and the native watcher platform channel.
/// Screens previously each did `final _api = WatcherHostApi();`
/// independently (app_picker_screen, permission_flow, scaffold_check_page)
/// — this replaces that with one instance, constructed once in main.dart
/// and passed down via constructor, so there's one place to fake for
/// widget tests and one place to see everything the UI can ask the
/// platform for.
class WatcherRepository {
  WatcherRepository([WatcherHostApi? api]) : _api = api ?? WatcherHostApi();

  final WatcherHostApi _api;

  Future<WatcherStatusDto> getStatus() => _api.getStatus();
  Future<void> startWatcher() => _api.startWatcher();
  Future<void> debugGrant(String pkg, int minutes, String? intentText) => _api.debugGrant(pkg, minutes, intentText);

  Future<List<AppInfoDto>> getLaunchableApps() => _api.getLaunchableApps();
  Future<List<WatchedAppConfigDto>> getWatchedApps() => _api.getWatchedApps();
  Future<void> saveWatchedApps(List<WatchedAppConfigDto> apps) => _api.saveWatchedApps(apps);

  Future<PermissionSnapshotDto> getPermissionSnapshot() => _api.getPermissionSnapshot();
  Future<void> openUsageAccessSettings() => _api.openUsageAccessSettings();
  Future<void> openOverlayPermissionSettings() => _api.openOverlayPermissionSettings();
  Future<void> requestNotificationPermission() => _api.requestNotificationPermission();
  Future<void> requestBatteryExemption() => _api.requestBatteryExemption();

  Future<bool> isOnboardingComplete() => _api.isOnboardingComplete();
  Future<void> setOnboardingComplete() => _api.setOnboardingComplete();
}
