import 'package:flutter/material.dart';

import '../core/watcher_repository.dart';
import '../onboarding/permission_flow.dart';
import '../onboarding/permission_step_screen.dart';
import '../pigeon/watcher_api.g.dart';
import '../theme/grudge_theme.dart';

/// T-110 recovery screen (matches the ERR-1 Figma concept: full red
/// background, diagnosis card, "RESURRECT THE WATCH" CTA). Reached from
/// ScaffoldCheckPage's red banner. Reuses the exact PermissionStepScreen
/// built for onboarding — permission_step_screen.dart's own doc comment
/// flagged this as the intended reuse case back in T-109.
class WatchDownScreen extends StatefulWidget {
  const WatchDownScreen({super.key, required this.repo});

  final WatcherRepository repo;

  @override
  State<WatchDownScreen> createState() => _WatchDownScreenState();
}

class _WatchDownScreenState extends State<WatchDownScreen> {
  WatcherStatusDto? _status;

  @override
  void initState() {
    super.initState();
    _diagnose();
  }

  Future<void> _diagnose() async {
    final status = await widget.repo.getStatus();
    if (!mounted) return;
    setState(() => _status = status);
  }

  Future<void> _recover() async {
    final status = _status;
    if (status == null) return;

    if (!status.hasUsageAccess || !status.hasOverlayPermission) {
      final config = buildPermissionSteps(widget.repo)[status.hasUsageAccess ? 1 : 0];
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (routeContext) => PermissionStepScreen(
            config: config,
            onGranted: () => Navigator.of(routeContext).pop(),
          ),
        ),
      );
    } else {
      await widget.repo.startWatcher();
      await Future.delayed(const Duration(milliseconds: 800));
    }
    await _diagnose();
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    return Scaffold(
      backgroundColor: GrudgeColors.red,
      appBar: AppBar(backgroundColor: GrudgeColors.red, elevation: 0, iconTheme: const IconThemeData(color: GrudgeColors.paper)),
      body: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          child: status == null
              ? const Center(child: CircularProgressIndicator(color: GrudgeColors.paper))
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('THE WATCH\nIS DOWN.', style: grudgeHeadline(size: 36, color: GrudgeColors.paper)),
                    const SizedBox(height: 12),
                    const Text(
                      "It stopped watching, silently. Here's exactly why.",
                      style: TextStyle(color: GrudgeColors.paper, fontSize: 15),
                    ),
                    const SizedBox(height: 24),
                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: hardShadowBox(fill: GrudgeColors.paper, border: GrudgeColors.ink),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          _DiagnosisRow('Usage access', status.hasUsageAccess),
                          _DiagnosisRow('Display over other apps', status.hasOverlayPermission),
                          _DiagnosisRow(
                            'Background service',
                            status.isRunning,
                            failLabel: 'KILLED BY BATTERY SAVER',
                          ),
                        ],
                      ),
                    ),
                    const Spacer(),
                    GrudgeLightButton(label: 'Resurrect the watch', onPressed: _recover),
                  ],
                ),
        ),
      ),
    );
  }
}

class _DiagnosisRow extends StatelessWidget {
  const _DiagnosisRow(this.label, this.ok, {this.failLabel});

  final String label;
  final bool ok;
  final String? failLabel;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            ok ? '✓' : '✗',
            style: TextStyle(
              color: ok ? GrudgeColors.green : GrudgeColors.red,
              fontWeight: FontWeight.w900,
              fontSize: 16,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              ok || failLabel == null ? label : '$label — $failLabel',
              style: const TextStyle(color: GrudgeColors.ink, fontWeight: FontWeight.w700, fontSize: 15),
            ),
          ),
        ],
      ),
    );
  }
}
