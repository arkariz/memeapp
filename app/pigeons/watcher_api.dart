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
/// WatcherCore. startWatcher is a dev/test affordance for T-102 — the real
/// product starts the service from onboarding completion (T-109), not a
/// manual button.
@HostApi()
abstract class WatcherHostApi {
  WatcherStatusDto getStatus();
  void startWatcher();
}
