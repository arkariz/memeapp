import 'dart:async';

import '../pigeon/watcher_api.g.dart';

/// Android delivers the POST_NOTIFICATIONS runtime-permission result via an
/// activity callback (onRequestPermissionsResult), not a return value from
/// the request call itself (MainActivity.kt relays it through
/// WatcherFlutterApi.onNotificationPermissionResult). This bridge turns
/// that single Kotlin->Dart callback into a broadcast stream any onboarding
/// screen can listen to while it's mounted, without needing to know about
/// each other or coordinate a single owner.
class NotificationPermissionBridge extends WatcherFlutterApi {
  NotificationPermissionBridge._();

  static final NotificationPermissionBridge instance = NotificationPermissionBridge._();

  final _controller = StreamController<bool>.broadcast();

  Stream<bool> get results => _controller.stream;

  /// Call once at app startup (see main.dart).
  void register() {
    WatcherFlutterApi.setUp(this);
  }

  @override
  void onNotificationPermissionResult(bool granted) {
    _controller.add(granted);
  }
}
