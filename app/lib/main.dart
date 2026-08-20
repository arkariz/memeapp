import 'package:flutter/material.dart';
import 'src/pigeon/watcher_api.g.dart';

void main() {
  runApp(const GrudgeApp());
}

/// T-101/T-102 scaffold entry point. Proves the Flutter -> Pigeon ->
/// native-core pipeline compiles and round-trips end to end. Real
/// onboarding/home UI per the Figma designs is T-109/T-201 — deliberately
/// not built here.
class GrudgeApp extends StatelessWidget {
  const GrudgeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Grudge',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.amber)),
      home: const ScaffoldCheckPage(),
    );
  }
}

class ScaffoldCheckPage extends StatefulWidget {
  const ScaffoldCheckPage({super.key});

  @override
  State<ScaffoldCheckPage> createState() => _ScaffoldCheckPageState();
}

class _ScaffoldCheckPageState extends State<ScaffoldCheckPage> {
  final _api = WatcherHostApi();
  String _statusText = 'Not queried yet';

  Future<void> _queryStatus() async {
    final status = await _api.getStatus();
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
    await _api.startWatcher();
    await _queryStatus();
  }

  Future<void> _debugGrant() async {
    // 1 min, not 5 — a dev/test tool is more useful when it doesn't require
    // waiting 5 real minutes to see the roast overlay fire (T-106).
    await _api.debugGrant('com.android.chrome', 1, 'testing reload-on-restart');
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
