import 'package:flutter/material.dart';

import '../core/watcher_repository.dart';
import '../home/scaffold_check_page.dart';
import 'notification_permission_bridge.dart';
import 'permission_step_screen.dart';

/// The "4 grant steps" from T-109's backlog line, in PRD order: Usage
/// Access, Display-over-other-apps (overlay), notifications, then the
/// battery-optimization exemption. Usage Access and Overlay are hard
/// blockers — the core loop cannot function without them, so those two
/// steps offer no skip. Notifications and battery exemption matter for
/// reliability but aren't launch-blocking the same way, so they offer
/// "Skip for now".
///
/// Copy rewritten 2026-08-20 to match a Figma screenshot: each step leads
/// with what the OS permission is ACTUALLY for, in plain, funny, slightly
/// unhinged first-person voice ("I need to see your screen time") rather
/// than a dry compliance explanation, then backs it with a ✓/✗ checklist
/// that does the actual reassuring (scoped, on-device, can't-do-X).
List<PermissionStepConfig> buildPermissionSteps(WatcherRepository repo) => [
  PermissionStepConfig(
    stepIndex: 1,
    totalSteps: 4,
    imageAsset: 'assets/onboarding/usage_access.png',
    title: 'I NEED TO SEE\nYOUR SCREEN TIME.',
    subtitle: 'Android calls it "Usage Access." We call it evidence.',
    checklist: const [
      ChecklistLine(true, "Counts your minutes. That's the whole job."),
      ChecklistLine(true, 'Everything stays on your phone. No cloud, no account.'),
      ChecklistLine(false, "Can't read your messages, feeds, or shame in detail."),
    ],
    ctaLabel: 'Grant Usage Access',
    caption: "It literally can't work without this.",
    recoveryBody:
        'Still not granted. Find Grudge in the Usage Access list and switch it on — '
        'no evidence, no roast, no app.',
    checkGranted: () async => (await repo.getStatus()).hasUsageAccess,
    onRequest: repo.openUsageAccessSettings,
  ),
  PermissionStepConfig(
    stepIndex: 2,
    totalSteps: 4,
    imageAsset: 'assets/onboarding/overlay.gif',
    title: 'I NEED TO DRAW\nON YOUR SCREEN.',
    subtitle: 'Android calls it "Display over other apps." We call it the stage.',
    checklist: const [
      ChecklistLine(true, "Full-screen roast the second your time's up."),
      ChecklistLine(true, "No notification to swipe away and pretend you didn't see."),
      ChecklistLine(false, "Can't listen, record, or touch anything else on your screen."),
    ],
    ctaLabel: 'Grant display access',
    caption: 'No overlay, no roast. Just a quiet, unsatisfying silence.',
    recoveryBody:
        'Still not granted. Find Grudge and enable "Allow display over other apps" — '
        'without this, your time just runs out with nothing to show for it.',
    checkGranted: () async => (await repo.getStatus()).hasOverlayPermission,
    onRequest: repo.openOverlayPermissionSettings,
  ),
  PermissionStepConfig(
    stepIndex: 3,
    totalSteps: 4,
    imageAsset: 'assets/onboarding/notifications.gif',
    title: 'I NEED A TINY\nLITTLE NOTIFICATION.',
    subtitle: 'Android calls it a "foreground service." We call it staying employed.',
    checklist: const [
      ChecklistLine(true, 'One quiet "Grudge is watching" note while it works.'),
      ChecklistLine(true, "Required by Android to keep watching — not our idea of fun either."),
      ChecklistLine(false, "Won't spam you with \"come back!\" guilt-trip pings."),
    ],
    ctaLabel: 'Allow notifications',
    caption: 'Skip this and Android eventually fires the watcher.',
    recoveryBody: 'Still not enabled. You can turn this on later from Android\'s app settings.',
    checkGranted: () async => (await repo.getPermissionSnapshot()).hasNotificationPermission,
    onRequest: repo.requestNotificationPermission,
    resultStream: NotificationPermissionBridge.instance.results,
    allowSkip: true,
  ),
  PermissionStepConfig(
    stepIndex: 4,
    totalSteps: 4,
    imageAsset: 'assets/onboarding/battery.gif',
    title: "DON'T LET YOUR\nPHONE KILL ME.",
    subtitle: 'Some phones murder background apps to save 4% battery. We\'d like to survive the night.',
    checklist: const [
      ChecklistLine(true, 'Keeps the watcher alive while you sleep.'),
      ChecklistLine(true, 'One ask, once — no recurring battery nags after this.'),
      ChecklistLine(false, "Can't stop your phone from dying for any other reason."),
    ],
    ctaLabel: 'Request battery exemption',
    caption: "Skippable. But some phones will kill it anyway — that's on them.",
    recoveryBody:
        'Still not exempted. You can grant this later, but on some phones the watcher '
        'may silently stop — that\'s what the "watch is down" banner is for.',
    checkGranted: () async => (await repo.getPermissionSnapshot()).isIgnoringBatteryOptimizations,
    onRequest: repo.requestBatteryExemption,
    allowSkip: true,
  ),
];

class PermissionFlow extends StatefulWidget {
  const PermissionFlow({super.key, required this.repo});

  final WatcherRepository repo;

  @override
  State<PermissionFlow> createState() => _PermissionFlowState();
}

class _PermissionFlowState extends State<PermissionFlow> {
  late final _steps = buildPermissionSteps(widget.repo);
  int _index = 0;

  void _advance() {
    if (_index == _steps.length - 1) {
      _finish();
      return;
    }
    setState(() => _index += 1);
  }

  Future<void> _finish() async {
    await widget.repo.setOnboardingComplete();
    await widget.repo.startWatcher();
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => ScaffoldCheckPage(repo: widget.repo)),
      (route) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    // Keyed so Flutter tears down and rebuilds PermissionStepScreen's state
    // (including its WidgetsBindingObserver / stream subscription) between
    // steps instead of reusing one instance across different configs.
    return PermissionStepScreen(
      key: ValueKey(_index),
      config: _steps[_index],
      onGranted: _advance,
      onSkipped: _advance,
    );
  }
}
