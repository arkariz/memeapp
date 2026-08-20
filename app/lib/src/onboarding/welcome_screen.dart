import 'package:flutter/material.dart';

import '../core/watcher_repository.dart';
import '../theme/grudge_theme.dart';
import 'app_picker_screen.dart';

/// OB-0, rebuilt to match the actual Figma screenshot the user supplied
/// (2026-08-20) — full-bleed black wordmark bar, a slightly rotated yellow
/// highlight behind the headline (the Figma file's hand-drawn-marker
/// motif), and the sharper copy/CTA from that design. Replaces the
/// earlier version built from PRD prose alone while Figma MCP was
/// rate-limited.
class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({super.key, required this.repo});

  final WatcherRepository repo;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: GrudgeColors.paper,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Full-bleed to the very top edge, behind the status bar — matches
          // the screenshot's black bar starting at y=0, not below the notch.
          Container(
            color: GrudgeColors.ink,
            padding: EdgeInsets.fromLTRB(24, MediaQuery.of(context).padding.top + 20, 24, 20),
            child: const Text(
              'grudge',
              style: TextStyle(color: GrudgeColors.paper, fontSize: 20, fontWeight: FontWeight.w900),
            ),
          ),
          Expanded(
            child: SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(24, 40, 24, 24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Spacer(flex: 3),
                    Transform.rotate(
                      angle: -0.035,
                      child: Container(
                        padding: const EdgeInsets.all(16),
                        decoration: hardShadowBox(fill: GrudgeColors.yellow),
                        child: Text(
                          'YOUR SCREEN TIME IS\nABOUT TO GET ROASTED.',
                          style: grudgeHeadline(size: 32),
                        ),
                      ),
                    ),
                    const SizedBox(height: 24),
                    Text(
                      "Set a limit. Blow past it. Get memed with your own words. "
                      "Stop sooner.\n\nThat's it. That's the app.",
                      style: grudgeBody(size: 16),
                    ),
                    const Spacer(flex: 5),
                    GrudgeDarkButton(
                      label: "Let's go",
                      onPressed: () {
                        Navigator.of(context).push(
                          MaterialPageRoute(builder: (_) => AppPickerScreen(repo: repo)),
                        );
                      },
                    ),
                    const SizedBox(height: 10),
                    SizedBox(
                      width: double.infinity,
                      child: Text(
                        'Setup takes 2 minutes. Unlike TikTok.',
                        textAlign: TextAlign.center,
                        style: grudgeBody(size: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
