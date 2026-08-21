import 'package:flutter/material.dart';

import 'src/core/watcher_repository.dart';
import 'src/home/home_screen.dart';
import 'src/onboarding/notification_permission_bridge.dart';
import 'src/onboarding/welcome_screen.dart';
import 'src/theme/grudge_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  NotificationPermissionBridge.instance.register();
  runApp(GrudgeApp(repo: WatcherRepository()));
}

class GrudgeApp extends StatelessWidget {
  const GrudgeApp({super.key, required this.repo});

  final WatcherRepository repo;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Grudge',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.amber)),
      home: _AppBootstrap(repo: repo),
    );
  }
}

/// T-109: routes to onboarding on first run, straight to the scaffold on
/// every later launch. The check itself (OnboardingPrefs, a SharedPreferences
/// flag) lives entirely on the native side — see WatcherRepository.isOnboardingComplete.
class _AppBootstrap extends StatelessWidget {
  const _AppBootstrap({required this.repo});

  final WatcherRepository repo;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<bool>(
      future: repo.isOnboardingComplete(),
      builder: (context, snapshot) {
        if (!snapshot.hasData) {
          return const Scaffold(
            backgroundColor: GrudgeColors.paper,
            body: Center(child: CircularProgressIndicator(color: GrudgeColors.ink)),
          );
        }
        return snapshot.data! ? HomeScreen(repo: repo) : WelcomeScreen(repo: repo);
      },
    );
  }
}
