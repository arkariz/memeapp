import 'package:flutter/material.dart';

import 'src/home/scaffold_check_page.dart';
import 'src/onboarding/notification_permission_bridge.dart';
import 'src/onboarding/welcome_screen.dart';
import 'src/pigeon/watcher_api.g.dart';
import 'src/theme/grudge_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  NotificationPermissionBridge.instance.register();
  runApp(const GrudgeApp());
}

class GrudgeApp extends StatelessWidget {
  const GrudgeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Grudge',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.amber)),
      home: const _AppBootstrap(),
    );
  }
}

/// T-109: routes to onboarding on first run, straight to the scaffold on
/// every later launch. The check itself (OnboardingPrefs, a SharedPreferences
/// flag) lives entirely on the native side — see WatcherHostApi.isOnboardingComplete.
class _AppBootstrap extends StatelessWidget {
  const _AppBootstrap();

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<bool>(
      future: WatcherHostApi().isOnboardingComplete(),
      builder: (context, snapshot) {
        if (!snapshot.hasData) {
          return const Scaffold(
            backgroundColor: GrudgeColors.paper,
            body: Center(child: CircularProgressIndicator(color: GrudgeColors.ink)),
          );
        }
        return snapshot.data! ? const ScaffoldCheckPage() : const WelcomeScreen();
      },
    );
  }
}
