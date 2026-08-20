import 'package:flutter/material.dart';

/// Brutalist palette matching the native overlays (RoastOverlayController,
/// IntentOverlayController) and the Figma "core" variable collection —
/// flat ink/paper/yellow, no gradients, hard offset shadows, square
/// corners. Kept as plain constants (not a ThemeData color scheme) since
/// onboarding is a handful of custom-built screens, not Material widgets
/// reskinned — same choice the native overlays made.
abstract final class GrudgeColors {
  static const ink = Color(0xFF0D0D0D);
  static const paper = Color(0xFFFFFBF8);
  static const yellow = Color(0xFFFFE600);
  static const gray = Color(0xFF6B6B6B);
  static const red = Color(0xFFFF2E00);
  static const green = Color(0xFF16DB65);
}

/// The recipe used throughout the native overlays and the Figma file:
/// thick ink border + a hard (non-blurred) offset shadow, square corners.
BoxDecoration hardShadowBox({
  Color fill = GrudgeColors.paper,
  Color border = GrudgeColors.ink,
  double borderWidth = 3,
  Offset shadowOffset = const Offset(5, 6),
}) {
  return BoxDecoration(
    color: fill,
    border: Border.all(color: border, width: borderWidth),
    boxShadow: [BoxShadow(color: border, offset: shadowOffset, blurRadius: 0)],
  );
}

/// btn/dark from the Figma component set: solid ink fill, paper text.
class GrudgeDarkButton extends StatelessWidget {
  const GrudgeDarkButton({super.key, required this.label, required this.onPressed});

  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return _GrudgeButtonBase(
      label: label,
      onPressed: onPressed,
      fill: GrudgeColors.ink,
      textColor: GrudgeColors.paper,
      border: GrudgeColors.ink,
    );
  }
}

/// btn/light from the Figma component set: paper fill, ink text/border.
class GrudgeLightButton extends StatelessWidget {
  const GrudgeLightButton({super.key, required this.label, required this.onPressed});

  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return _GrudgeButtonBase(
      label: label,
      onPressed: onPressed,
      fill: GrudgeColors.paper,
      textColor: GrudgeColors.ink,
      border: GrudgeColors.ink,
    );
  }
}

class _GrudgeButtonBase extends StatelessWidget {
  const _GrudgeButtonBase({
    required this.label,
    required this.onPressed,
    required this.fill,
    required this.textColor,
    required this.border,
  });

  final String label;
  final VoidCallback? onPressed;
  final Color fill;
  final Color textColor;
  final Color border;

  @override
  Widget build(BuildContext context) {
    final disabled = onPressed == null;
    return GestureDetector(
      onTap: onPressed,
      child: Opacity(
        opacity: disabled ? 0.4 : 1,
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(vertical: 18),
          decoration: hardShadowBox(fill: fill, border: border),
          alignment: Alignment.center,
          child: Text(
            label.toUpperCase(),
            textAlign: TextAlign.center,
            style: TextStyle(
              color: textColor,
              fontSize: 16,
              fontWeight: FontWeight.w900,
              letterSpacing: 0.5,
            ),
          ),
        ),
      ),
    );
  }
}

/// The big uppercase headline style used everywhere in the native overlays
/// and Figma frames (system bold sans, not a bundled Anton asset — the
/// native side doesn't ship a real Anton font file either, see
/// RoastOverlayController/IntentOverlayController's Typeface.BOLD use).
TextStyle grudgeHeadline({double size = 34, Color color = GrudgeColors.ink}) => TextStyle(
  color: color,
  fontSize: size,
  fontWeight: FontWeight.w900,
  height: 1.05,
);

TextStyle grudgeBody({double size = 15, Color color = GrudgeColors.gray}) =>
    TextStyle(color: color, fontSize: size, height: 1.4);
