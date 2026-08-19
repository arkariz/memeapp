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
  WatcherStatusDto({required this.isRunning, this.heartbeatAgeMs});

  bool isRunning;
  int? heartbeatAgeMs;
}

/// Dart -> Kotlin: queries the app module implements against WatcherCore.
@HostApi()
abstract class WatcherHostApi {
  WatcherStatusDto getStatus();
}
