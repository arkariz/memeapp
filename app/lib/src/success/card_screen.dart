import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../core/watcher_repository.dart';
import '../pigeon/watcher_api.g.dart';
import '../theme/bonked_theme.dart';

/// T-202: the one Flutter-rendered surface with a share affordance in the
/// whole app — PRD P0-5: "no share affordance on the roast, ever; sharing
/// is success-side only." Two variants share this layout: BEATEN (a single
/// session that beat its estimate) and STREAK_MILESTONE (current streak
/// just became a new personal best). Shown once per event — HomeScreen
/// calls WatcherRepository.acknowledgeCard on dismissal either way,
/// shared or not, so the same win never re-prompts on the next open.
class CardScreen extends StatefulWidget {
  const CardScreen({super.key, required this.card, this.repo});

  final CardDto card;

  /// T-203: optional so existing/test call sites without analytics wiring
  /// don't need a fake repo just to construct this screen.
  final WatcherRepository? repo;

  @override
  State<CardScreen> createState() => _CardScreenState();
}

class _CardScreenState extends State<CardScreen> {
  final _cardKey = GlobalKey();
  bool _sharing = false;

  bool get _isBeaten => widget.card.kind == 'BEATEN';

  String get _eyebrow => _isBeaten ? 'ESTIMATE BEATEN' : 'NEW MILESTONE';

  String get _caption {
    final pool = _isBeaten ? _beatenCaptions : _milestoneCaptions;
    final index = widget.card.referenceId.abs() % pool.length;
    return pool[index];
  }

  Future<void> _share() async {
    if (_sharing) return;
    setState(() => _sharing = true);
    try {
      final boundary = _cardKey.currentContext?.findRenderObject() as RenderRepaintBoundary?;
      if (boundary == null) return;
      final image = await boundary.toImage(pixelRatio: 3);
      final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
      if (byteData == null) return;
      final bytes = byteData.buffer.asUint8List();

      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/bonked_card_${widget.card.referenceId}.png');
      await file.writeAsBytes(bytes);

      await SharePlus.instance.share(
        ShareParams(
          files: [XFile(file.path)],
          text: _isBeaten
              ? "Said ${widget.card.grantedMin} minutes, took ${widget.card.takenMin}. Bonked is watching."
              : "${widget.card.streakCount}-day streak with Bonked. It roasts, I resist.",
        ),
      );
      // Fires on share-sheet invocation, not actual completion — SharePlus
      // doesn't expose whether the user followed through past the sheet.
      await widget.repo?.logAnalyticsEvent('card_shared', {'type': widget.card.kind});
    } finally {
      if (mounted) setState(() => _sharing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: BonkedColors.yellow,
      body: SafeArea(
        child: Stack(
          children: [
            SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 56, 24, 24),
              child: Column(
                children: [
                  Text(
                    _eyebrow,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: BonkedColors.ink,
                      fontSize: 13,
                      fontWeight: FontWeight.w900,
                      letterSpacing: 1.5,
                    ),
                  ),
                  const SizedBox(height: 24),
                  RepaintBoundary(key: _cardKey, child: _Card(card: widget.card, isBeaten: _isBeaten, caption: _caption)),
                  const SizedBox(height: 32),
                  BonkedDarkButton(
                    label: _sharing ? 'RENDERING...' : 'POST MY RESTRAINT',
                    onPressed: _sharing ? null : _share,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    "Wins get a share button. Roasts don't.",
                    textAlign: TextAlign.center,
                    style: bonkedBody(size: 12, color: BonkedColors.ink.withValues(alpha: 0.6)),
                  ),
                ],
              ),
            ),
            Positioned(
              top: 4,
              left: 4,
              child: IconButton(
                onPressed: () => Navigator.of(context).pop(),
                icon: const Icon(Icons.close, color: BonkedColors.ink),
                tooltip: 'Dismiss',
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.card, required this.isBeaten, required this.caption});

  final CardDto card;
  final bool isBeaten;
  final String caption;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: hardShadowBox(shadowOffset: const Offset(6, 8)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            color: BonkedColors.ink,
            child: const Text(
              'Bonked · CERTIFIED',
              style: TextStyle(color: BonkedColors.paper, fontSize: 11, fontWeight: FontWeight.w900, letterSpacing: 0.5),
            ),
          ),
          const SizedBox(height: 16),
          _MemeBox(assetName: isBeaten ? 'beaten' : 'streak'),
          if (isBeaten) ...[
            Text('SAID ${card.grantedMin} MINUTES.', style: bonkedHeadline(size: 28)),
            Text('TOOK ${card.takenMin}.', style: bonkedHeadline(size: 28, color: BonkedColors.green)),
          ] else ...[
            Text('${card.streakCount} DAYS.', style: bonkedHeadline(size: 28)),
            const Text('IN A ROW.', style: TextStyle(color: BonkedColors.green, fontSize: 28, fontWeight: FontWeight.w900, height: 1.05)),
          ],
          const SizedBox(height: 12),
          Text(caption, style: bonkedBody(size: 15)),
          const SizedBox(height: 16),
          const Divider(color: BonkedColors.ink, thickness: 1.5, height: 1),
          const SizedBox(height: 14),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '🔥 ${card.streakCount}-DAY STREAK',
                style: const TextStyle(color: BonkedColors.ink, fontWeight: FontWeight.w900, fontSize: 14),
              ),
              Text(_formatDate(card.dateIso), style: bonkedBody(size: 13)),
            ],
          ),
        ],
      ),
    );
  }
}

/// StatefulWidget rather than an Image.asset errorBuilder: AspectRatio
/// reserves its box's height from the incoming width constraint the
/// moment it's laid out, regardless of what its child eventually paints —
/// an errorBuilder returning SizedBox.shrink() still sits inside that
/// reserved box, leaving a large blank gap on the card. Checking the
/// asset's existence up front and skipping the AspectRatio box entirely
/// when it's missing is what actually collapses the space, matching the
/// native roast overlay's memeSection (tries the load first, only builds
/// the box if it succeeds).
class _MemeBox extends StatefulWidget {
  const _MemeBox({required this.assetName});

  final String assetName;

  @override
  State<_MemeBox> createState() => _MemeBoxState();
}

class _MemeBoxState extends State<_MemeBox> {
  bool _exists = false;

  @override
  void initState() {
    super.initState();
    _checkExists();
  }

  Future<void> _checkExists() async {
    try {
      await rootBundle.load('assets/success_pack/${widget.assetName}.png');
      if (mounted) setState(() => _exists = true);
    } catch (_) {
      // Missing meme must never block showing/sharing the card.
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_exists) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 20),
      child: ClipRect(
        child: AspectRatio(
          aspectRatio: 3 / 2,
          child: Image.asset('assets/success_pack/${widget.assetName}.png', fit: BoxFit.cover),
        ),
      ),
    );
  }
}

const _beatenCaptions = [
  'character development.',
  'growth.',
  'look at you.',
  'unprecedented.',
  'the algorithm is confused.',
  'science cannot explain this.',
];

const _milestoneCaptions = [
  'a pattern is forming.',
  'this is who you are now.',
  'streaks like this don\'t happen by accident.',
  'consistency, apparently.',
];

const _months = [
  'JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC',
];

String _formatDate(String isoDate) {
  final parts = isoDate.split('-');
  if (parts.length != 3) return isoDate;
  final month = int.tryParse(parts[1]);
  final day = int.tryParse(parts[2]);
  if (month == null || day == null || month < 1 || month > 12) return isoDate;
  return '${_months[month - 1]} $day';
}
