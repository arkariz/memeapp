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
}
