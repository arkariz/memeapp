import 'package:flutter/material.dart';

import '../core/watcher_repository.dart';
import '../pigeon/watcher_api.g.dart';
import '../success/card_screen.dart';
import '../theme/bonked_theme.dart';
import 'scaffold_check_page.dart';
import 'watch_down_screen.dart';

/// T-201: the real post-onboarding landing screen (Figma 05), replacing
/// ScaffoldCheckPage in that role — that screen still exists as a dev-tools
/// page (Query WatcherCore / Start watcher / Debug grant), reachable via the
/// settings gear here rather than deleted, since nothing else exposes those
/// affordances yet.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.repo});

  final WatcherRepository repo;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  HomeSnapshotDto? _snapshot;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _checkForPendingCard();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Same T-110 reasoning as ScaffoldCheckPage: catch a watcher that died
    // (or a new day's streak/usage numbers) on every resume, not just once.
    if (state == AppLifecycleState.resumed) {
      _refresh();
      _checkForPendingCard();
    }
  }

  Future<void> _refresh() async {
    final snapshot = await widget.repo.getHomeSnapshot();
    if (!mounted) return;
    setState(() => _snapshot = snapshot);
  }

  /// T-202: a beaten estimate or a new streak-best surfaces here, the
  /// first Home open after it happened — the native side has no UI of its
  /// own to show it from (this is a success moment, no overlay for it by
  /// design). Acknowledged unconditionally on dismissal, shared or not, so
  /// it never re-prompts for the same event.
  Future<void> _checkForPendingCard() async {
    final card = await widget.repo.getPendingCard();
    if (card == null || !mounted) return;
    await widget.repo.logAnalyticsEvent('card_generated', {'type': card.kind});
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => CardScreen(card: card, repo: widget.repo), fullscreenDialog: true),
    );
    await widget.repo.acknowledgeCard(card.kind, card.referenceId);
    if (!mounted) return;
    await _refresh();
  }

  Future<void> _openWatchDownScreen() async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => WatchDownScreen(repo: widget.repo)));
    await _refresh();
  }

  Future<void> _openDevTools() async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => ScaffoldCheckPage(repo: widget.repo)));
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    return Scaffold(
      backgroundColor: BonkedColors.paper,
      body: SafeArea(
        bottom: false,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _Header(onSettingsTap: _openDevTools),
            if (snapshot == null)
              const Expanded(child: Center(child: CircularProgressIndicator(color: BonkedColors.ink)))
            else ...[
              _WatchStatusBanner(
                isUp: snapshot.isRunning && snapshot.hasUsageAccess && snapshot.hasOverlayPermission,
                watcherStartedAtMs: snapshot.watcherStartedAtMs,
                onTap: _openWatchDownScreen,
              ),
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(24, 32, 24, 32),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _StreakSection(current: snapshot.streakCurrent, best: snapshot.streakBest),
                      const SizedBox(height: 40),
                      _TodaysDamage(apps: snapshot.apps),
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.onSettingsTap});

  final VoidCallback onSettingsTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: BonkedColors.ink,
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          const Expanded(
            child: Text(
              'Bonked — Bro, Put the Phone Down',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: BonkedColors.paper, fontSize: 22, fontWeight: FontWeight.w900),
            ),
          ),
          IconButton(
            onPressed: onSettingsTap,
            icon: const Icon(Icons.settings, color: BonkedColors.paper),
            tooltip: 'Dev tools',
          ),
        ],
      ),
    );
  }
}

class _WatchStatusBanner extends StatelessWidget {
  const _WatchStatusBanner({required this.isUp, required this.watcherStartedAtMs, required this.onTap});

  final bool isUp;
  final int? watcherStartedAtMs;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final since = watcherStartedAtMs != null ? _formatTimeOfDay(DateTime.fromMillisecondsSinceEpoch(watcherStartedAtMs!)) : null;
    return Material(
      color: isUp ? BonkedColors.green : BonkedColors.red,
      child: InkWell(
        onTap: isUp ? null : onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          decoration: const BoxDecoration(
            border: Border(
              top: BorderSide(color: BonkedColors.ink, width: 3),
              bottom: BorderSide(color: BonkedColors.ink, width: 3),
            ),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Flexible(
                child: Text(
                  isUp ? 'THE WATCH IS UP ✓' : 'THE WATCH IS DOWN ✗ — tap to fix',
                  style: const TextStyle(color: BonkedColors.ink, fontWeight: FontWeight.w900, fontSize: 15),
                ),
              ),
              if (isUp && since != null)
                Text('since $since', style: const TextStyle(color: BonkedColors.ink, fontSize: 13)),
            ],
          ),
        ),
      ),
    );
  }
}

String _formatTimeOfDay(DateTime dt) {
  final hour12 = dt.hour % 12 == 0 ? 12 : dt.hour % 12;
  final minute = dt.minute.toString().padLeft(2, '0');
  final period = dt.hour >= 12 ? 'PM' : 'AM';
  return '$hour12:$minute $period';
}

class _StreakSection extends StatelessWidget {
  const _StreakSection({required this.current, required this.best});

  final int current;
  final int best;

  @override
  Widget build(BuildContext context) {
    final String caption;
    if (best == 0) {
      caption = 'no streak yet — start one today';
    } else if (current >= best) {
      caption = 'new personal best! 🎉';
    } else {
      caption = 'personal best: $best — so close, so far';
    }
    return Column(
      children: [
        Text(
          '$current',
          style: const TextStyle(color: BonkedColors.ink, fontSize: 120, fontWeight: FontWeight.w900, height: 1),
        ),
        const SizedBox(height: 8),
        const Text(
          'DAY STREAK 🔥',
          style: TextStyle(color: BonkedColors.ink, fontSize: 20, fontWeight: FontWeight.w900),
        ),
        const SizedBox(height: 6),
        Text(caption, style: bonkedBody(size: 14)),
      ],
    );
  }
}

class _TodaysDamage extends StatelessWidget {
  const _TodaysDamage({required this.apps});

  final List<AppUsageDto> apps;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          "TODAY'S DAMAGE",
          style: TextStyle(color: BonkedColors.ink, fontSize: 13, fontWeight: FontWeight.w900, letterSpacing: 1.5),
        ),
        const SizedBox(height: 16),
        if (apps.isEmpty)
          Text('Nothing watched yet — add apps from onboarding.', style: bonkedBody())
        else
          for (final app in apps)
            Padding(padding: const EdgeInsets.only(bottom: 20), child: _AppUsageRow(app: app)),
      ],
    );
  }
}

class _AppUsageRow extends StatelessWidget {
  const _AppUsageRow({required this.app});

  final AppUsageDto app;

  @override
  Widget build(BuildContext context) {
    final over = app.budgetMin > 0 && app.usedMin > app.budgetMin;
    final fraction = app.budgetMin <= 0 ? 0.0 : (app.usedMin / app.budgetMin).clamp(0.0, 1.0);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(app.label, style: const TextStyle(color: BonkedColors.ink, fontSize: 16, fontWeight: FontWeight.w900)),
            Text(
              over ? '${app.usedMin} / ${app.budgetMin} — OVER' : '${app.usedMin} / ${app.budgetMin} MIN',
              style: TextStyle(
                color: over ? BonkedColors.red : BonkedColors.gray,
                fontSize: 14,
                fontWeight: over ? FontWeight.w900 : FontWeight.normal,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        Container(
          width: double.infinity,
          height: 14,
          decoration: BoxDecoration(border: Border.all(color: BonkedColors.ink, width: 2)),
          child: FractionallySizedBox(
            alignment: Alignment.centerLeft,
            widthFactor: over ? 1.0 : fraction,
            child: Container(color: over ? BonkedColors.red : BonkedColors.yellow),
          ),
        ),
      ],
    );
  }
}
