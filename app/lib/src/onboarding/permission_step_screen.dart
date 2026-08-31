import 'dart:async';

import 'package:flutter/material.dart';

import '../theme/grudge_theme.dart';

/// One line of the ✓/✗ checklist under the meme box — [positive] picks the
/// green check vs. red cross, [text] is the claim itself.
class ChecklistLine {
  const ChecklistLine(this.positive, this.text);

  final bool positive;
  final String text;
}

/// Generic, config-driven grant screen reused for all four T-109 permission
/// steps (Usage Access, Overlay, Notifications, Battery exemption) and
/// intended to be reusable later for T-110's "the watch is down" recovery
/// prompt, which needs the exact same grant-and-recheck shape for a single
/// revoked permission rather than the full onboarding chain.
///
/// Layout matches the 2026-08-20 Figma screenshot: a "BEGGING SEASON ·
/// STEP X OF N" eyebrow, a two-line headline, a yellow hard-shadow box
/// holding a real meme image per step (user-sourced — see
/// app/assets/onboarding/README.md for exactly which file each step
/// expects; deliberately NOT the roast_pack_v1 bundled illustration set,
/// see that README for why this is a different licensing exposure), an
/// "Android calls it X. We call it Y." subtitle, then the ✓/✗ checklist.
///
/// Revocation-recovery behavior (PRD P0-1): after the user is sent to
/// Settings (or shown the in-app system dialog) and returns, this screen
/// re-checks [checkGranted]. If still not granted, it switches into a
/// "still not enabled" recovery variant of the same screen rather than
/// silently advancing or silently retrying — the user needs to see that
/// their action didn't take effect.
class PermissionStepConfig {
  const PermissionStepConfig({
    required this.stepIndex,
    required this.totalSteps,
    required this.imageAsset,
    required this.title,
    required this.subtitle,
    required this.checklist,
    required this.ctaLabel,
    required this.caption,
    required this.recoveryBody,
    required this.checkGranted,
    required this.onRequest,
    this.resultStream,
    this.allowSkip = false,
    this.onLogStep,
  });

  final int stepIndex;
  final int totalSteps;

  /// Asset path, e.g. 'assets/onboarding/usage_access.jpg'. Rendered with
  /// an errorBuilder fallback so a missing file (not yet downloaded/added
  /// by the user) shows a clear placeholder instead of crashing the screen.
  final String imageAsset;
  final String title;
  final String subtitle;
  final List<ChecklistLine> checklist;
  final String ctaLabel;
  final String caption;
  final String recoveryBody;
  final Future<bool> Function() checkGranted;
  final VoidCallback onRequest;

  /// When set (notifications), the result arrives via this stream instead
  /// of an app-resume lifecycle check — see NotificationPermissionBridge's
  /// doc comment for why that permission can't use the same recheck-on-
  /// resume pattern the Settings-intent-based steps use.
  final Stream<bool>? resultStream;

  final bool allowSkip;

  /// T-203: fires once this step resolves — true on grant, false on
  /// still-not-granted or explicit skip. Optional so a config built
  /// without analytics wiring (e.g. in a test) doesn't need a no-op stub.
  final void Function(bool ok)? onLogStep;
}

class PermissionStepScreen extends StatefulWidget {
  const PermissionStepScreen({super.key, required this.config, required this.onGranted, this.onSkipped});

  final PermissionStepConfig config;
  final VoidCallback onGranted;
  final VoidCallback? onSkipped;

  @override
  State<PermissionStepScreen> createState() => _PermissionStepScreenState();
}

enum _StepPhase { initial, awaitingResult, stillNotGranted }

class _PermissionStepScreenState extends State<PermissionStepScreen> with WidgetsBindingObserver {
  _StepPhase _phase = _StepPhase.initial;
  StreamSubscription<bool>? _resultSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _resultSub = widget.config.resultStream?.listen(_handleResult);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _resultSub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Settings-intent-based steps (usage access, overlay, battery): the OS
    // takes over the whole screen, so returning to this app is a real
    // resume — recheck then. Steps with a resultStream (notifications)
    // skip this path; their result arrives via the callback instead.
    if (state == AppLifecycleState.resumed &&
        _phase == _StepPhase.awaitingResult &&
        widget.config.resultStream == null) {
      _recheck();
    }
  }

  Future<void> _handleResult(bool granted) async {
    widget.config.onLogStep?.call(granted);
    if (granted) {
      widget.onGranted();
    } else {
      setState(() => _phase = _StepPhase.stillNotGranted);
    }
  }

  Future<void> _recheck() async {
    final granted = await widget.config.checkGranted();
    if (!mounted) return;
    widget.config.onLogStep?.call(granted);
    if (granted) {
      widget.onGranted();
    } else {
      setState(() => _phase = _StepPhase.stillNotGranted);
    }
  }

  void _request() {
    setState(() => _phase = _StepPhase.awaitingResult);
    widget.config.onRequest();
  }

  void _skip() {
    widget.config.onLogStep?.call(false);
    widget.onSkipped?.call();
  }

  @override
  Widget build(BuildContext context) {
    final config = widget.config;
    final recovering = _phase == _StepPhase.stillNotGranted;
    return Scaffold(
      backgroundColor: GrudgeColors.paper,
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 32, 24, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (recovering) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: hardShadowBox(fill: GrudgeColors.red, borderWidth: 2, shadowOffset: const Offset(3, 4)),
                  child: const Text(
                    "STILL NOT ENABLED",
                    style: TextStyle(color: GrudgeColors.paper, fontWeight: FontWeight.w900, fontSize: 13),
                  ),
                ),
                const SizedBox(height: 16),
              ],
              Text(
                'BEGGING SEASON · ${config.stepIndex} OF ${config.totalSteps}',
                style: const TextStyle(
                  color: GrudgeColors.gray,
                  fontSize: 13,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 1.5,
                ),
              ),
              const SizedBox(height: 8),
              Text(config.title, style: grudgeHeadline(size: 30)),
              const SizedBox(height: 20),
              Container(
                width: double.infinity,
                height: 200,
                margin: EdgeInsets.symmetric(horizontal: 14),
                clipBehavior: Clip.hardEdge,
                decoration: hardShadowBox(fill: GrudgeColors.yellow),
                child: Image.asset(
                  config.imageAsset,
                  fit: BoxFit.contain,
                  errorBuilder: (context, error, stackTrace) => Center(
                    child: Text(
                      'Drop ${config.imageAsset.split('/').last}\ninto assets/onboarding/',
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: GrudgeColors.ink, fontWeight: FontWeight.w700, fontSize: 13),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 20),
              if (recovering)
                Text(config.recoveryBody, style: grudgeBody(size: 16))
              else ...[
                Text(config.subtitle, style: const TextStyle(color: GrudgeColors.ink, fontSize: 17, fontWeight: FontWeight.w700, height: 1.35)),
                const SizedBox(height: 18),
                ...config.checklist.map(
                  (line) => Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          line.positive ? '✓' : '✗',
                          style: TextStyle(
                            color: line.positive ? GrudgeColors.green : GrudgeColors.red,
                            fontWeight: FontWeight.w900,
                            fontSize: 16,
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(child: Text(line.text, style: grudgeBody(size: 15))),
                      ],
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 40),
              GrudgeDarkButton(label: recovering ? 'Try again' : config.ctaLabel, onPressed: _request),
              if (config.allowSkip && !recovering) ...[
                const SizedBox(height: 12),
                GrudgeLightButton(label: 'Skip for now', onPressed: _skip),
              ],
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                child: Text(config.caption, textAlign: TextAlign.center, style: grudgeBody(size: 13)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
