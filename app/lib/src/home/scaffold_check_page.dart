import 'package:flutter/material.dart';

import '../core/watcher_repository.dart';

/// T-101/T-102 scaffold entry point, still the post-onboarding landing
/// screen as of T-109 — the real Home screen (streaks, budget bars) is
/// T-201 (Phase 2). Moved out of main.dart so both main.dart (onboarding-
/// complete case) and the onboarding flow (fresh-completion case) can
/// reach it without a circular import.
class ScaffoldCheckPage extends StatefulWidget {
  const ScaffoldCheckPage({super.key, required this.repo});

  final WatcherRepository repo;

  @override
  State<ScaffoldCheckPage> createState() => _ScaffoldCheckPageState();
}

class _ScaffoldCheckPageState extends State<ScaffoldCheckPage> {
  String _statusText = 'Not queried yet';

  Future<void> _queryStatus() async {
    final status = await widget.repo.getStatus();
    setState(() {
      _statusText =
          'isRunning=${status.isRunning}\n'
          'heartbeatAgeMs=${status.heartbeatAgeMs}\n'
          'hasUsageAccess=${status.hasUsageAccess}\n'
          'hasOverlayPermission=${status.hasOverlayPermission}\n'
          'activeSessionCount=${status.activeSessionCount}';
    });
  }

  Future<void> _startWatcher() async {
    await widget.repo.startWatcher();
    await _queryStatus();
  }

  Future<void> _debugGrant() async {
    // 1 min, not 5 — a dev/test tool is more useful when it doesn't require
    // waiting 5 real minutes to see the roast overlay fire (T-106).
    await widget.repo.debugGrant('com.android.chrome', 1, 'testing reload-on-restart');
    await _queryStatus();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Grudge — T-103 scaffold check')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(_statusText, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _queryStatus,
              child: const Text('Query WatcherCore via Pigeon'),
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: _startWatcher,
              child: const Text(
                'Start watcher (dev/test only — real product\n'
                'starts this from onboarding, T-109)',
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: _debugGrant,
              child: const Text(
                'Debug grant: chrome, 1 min (dev/test only —\n'
                'real product grants from the intent overlay, T-104)',
                textAlign: TextAlign.center,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
