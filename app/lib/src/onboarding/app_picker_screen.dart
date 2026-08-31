import 'dart:math';

import 'package:flutter/material.dart';

import '../core/watcher_repository.dart';
import '../pigeon/watcher_api.g.dart';
import '../theme/bonked_theme.dart';
import 'permission_flow.dart';

/// OB-1: app picker + per-app daily budget. PRD P0-1: "App picker for
/// watched apps ... per-app daily budget setting." Data source is
/// WatcherRepository.getLaunchableApps (launcher-intent query, never
/// QUERY_ALL_PACKAGES — see WatcherCore.getLaunchableApps's own doc
/// comment). Pre-selects whatever's already in watched_app so re-entering
/// onboarding (e.g. from a future revocation-recovery flow) doesn't wipe a
/// prior selection.
class AppPickerScreen extends StatefulWidget {
  const AppPickerScreen({super.key, required this.repo, this.editMode = false});

  final WatcherRepository repo;

  /// True when reached from Home's "manage apps" entry point (post-onboarding
  /// edit) rather than the first-run flow: saves and pops back to Home
  /// instead of continuing into PermissionFlow, and shows a way back out
  /// without saving.
  final bool editMode;

  @override
  State<AppPickerScreen> createState() => _AppPickerScreenState();
}

class _AppPickerScreenState extends State<AppPickerScreen> {
  static const _defaultBudget = 15;

  final _searchController = TextEditingController();

  List<AppInfoDto> _apps = [];
  final Map<String, int> _selectedBudgets = {}; // pkg -> budgetMin
  bool _loading = true;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _load();
    _searchController.addListener(() => setState(() => _query = _searchController.text.trim().toLowerCase()));
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final results = await Future.wait([widget.repo.getLaunchableApps(), widget.repo.getWatchedApps()]);
    final apps = results[0] as List<AppInfoDto>;
    final existing = results[1] as List<WatchedAppConfigDto>;
    setState(() {
      _apps = apps;
      for (final e in existing) {
        if (e.enabled) _selectedBudgets[e.pkg] = e.budgetMin;
      }
      _loading = false;
    });
  }

  List<AppInfoDto> get _filtered =>
      _query.isEmpty ? _apps : _apps.where((a) => a.label.toLowerCase().contains(_query)).toList();

  Future<void> _continue() async {
    final toSave = _apps
        .map((a) {
          final budget = _selectedBudgets[a.pkg];
          return WatchedAppConfigDto(pkg: a.pkg, label: a.label, budgetMin: budget ?? _defaultBudget, enabled: budget != null);
        })
        .where((c) => c.enabled)
        .toList();
    await widget.repo.saveWatchedApps(toSave);
    if (!mounted) return;
    if (widget.editMode) {
      Navigator.of(context).pop();
    } else {
      Navigator.of(context).push(MaterialPageRoute(builder: (_) => PermissionFlow(repo: widget.repo)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final selectedCount = _selectedBudgets.length;
    return Scaffold(
      backgroundColor: BonkedColors.paper,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (widget.editMode)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: InkWell(
                        onTap: () => Navigator.of(context).pop(),
                        child: const Icon(Icons.arrow_back, color: BonkedColors.ink),
                      ),
                    ),
                  Text('PICK YOUR\nPOISON.', style: bonkedHeadline(size: 28)),
                  const SizedBox(height: 8),
                  Text(
                    'Which apps eat your evenings? Daily budget next to each.',
                    style: bonkedBody(),
                  ),
                  const SizedBox(height: 16),
                  Container(
                    decoration: hardShadowBox(borderWidth: 2, shadowOffset: const Offset(3, 4)),
                    child: TextField(
                      controller: _searchController,
                      decoration: const InputDecoration(
                        hintText: 'Search apps',
                        border: InputBorder.none,
                        contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator(color: BonkedColors.ink))
                  : ListView.builder(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      itemCount: _filtered.length,
                      itemBuilder: (context, index) => _AppRow(
                        app: _filtered[index],
                        budgetMin: _selectedBudgets[_filtered[index].pkg],
                        onToggle: () {
                          setState(() {
                            final pkg = _filtered[index].pkg;
                            if (_selectedBudgets.containsKey(pkg)) {
                              _selectedBudgets.remove(pkg);
                            } else {
                              _selectedBudgets[pkg] = _defaultBudget;
                            }
                          });
                        },
                        onBudgetChanged: (minutes) => setState(() => _selectedBudgets[_filtered[index].pkg] = minutes),
                      ),
                    ),
            ),
            Padding(
              padding: const EdgeInsets.all(24),
              child: BonkedDarkButton(
                label: selectedCount == 0
                    ? (widget.editMode ? 'SAVE (WATCHING NOTHING)' : 'PICK ONE. YOU KNOW THE ONE.')
                    : (widget.editMode ? 'SAVE ($selectedCount picked)' : 'LOCK IT IN ($selectedCount picked)'),
                onPressed: selectedCount == 0 && !widget.editMode ? null : _continue,
              ),
            ),
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: Text(
                'Budgets are editable. The judgment is not.',
                textAlign: TextAlign.center,
                style: bonkedBody(size: 13),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Budget slider range: 5 minutes to 3 hours (180 minutes), snapping to
/// 5-minute increments — matches the old chip picker's granularity while
/// covering a much wider span than the fixed 5/10/15/30 options did.
const _kBudgetMinMinutes = 5;
const _kBudgetMaxMinutes = 180;
const _kBudgetStepMinutes = 5;

/// Formats minutes with the unit that reads best at that size: "30M" below
/// an hour, "1H" / "1H30M" at or above it — never "90M" once hours make
/// more sense, and never drops the leftover minutes when they're non-zero.
String _formatBudget(int minutes) {
  if (minutes < 60) return '${minutes}M';
  final hours = minutes ~/ 60;
  final remainder = minutes % 60;
  return remainder == 0 ? '${hours}H' : '${hours}H${remainder}M';
}

/// The budget picked here is exactly what the roast overlay measures a
/// session against later — a bigger number today means a harsher roast
/// down the line, so the copy under the slider previews that escalation:
/// 5 tiers, each with several lines picked at random (see [_AppRowState]),
/// getting louder (shorter, punchier, redder) the more time is dialed in.
const _budgetTierLines = <List<String>>[
  [ // Tier 1: 5-25 min — Gentle Nudge
    'Just warming up your thumbs, I see.',
    'Alright, long enough to boil an egg.',
    "Good intentions. Let's see the execution.",
    'Just replying to a text, right? Right?',
  ],
  [ // Tier 2: 30-55 min — Raising an Eyebrow
    "Almost an hour. You sure it's just 'checking'?",
    'Enough time to brew coffee and... stare at a wall.',
    'A whole sitcom episode just evaporated.',
  ],
  [ // Tier 3: 1h-1h55m — Staring at the Numbers
    '60 MINUTES. That is 3,600 seconds, by the way.',
    'You could power nap twice in this window.',
    'A movie runtime, minus the post-credits scene.',
  ],
  [ // Tier 4: 2h-2h55m — Deadpan / Heavy
    'Two whole hours? Filming a documentary?',
    'This blocker app is starting to feel redundant.',
    'You could read a solid three chapters instead.',
    'TWO HOURS. A highly fascinating choice.',
  ],
  [ // Tier 5: 3h — Maximum Absurdity
    'THREE HOURS? Applying for a social media manager job?',
    'Enough time to fly from London to Rome.',
    'Congrats, you successfully maxed out the slider.',
    'Perfect duration to watch the entire Lord of the Rings credits.',
  ],
];

const _budgetTierColors = [BonkedColors.gray, BonkedColors.gray, BonkedColors.ink, BonkedColors.ink, BonkedColors.red];
const _budgetTierWeights = [
  FontWeight.w500,
  FontWeight.w600,
  FontWeight.w800,
  FontWeight.w900,
  FontWeight.w900,
];
const _budgetTierSizes = [12.0, 12.0, 13.0, 13.0, 14.0];

/// Tier boundaries matching [_budgetTierLines]'s comments — index 0..4.
int _budgetTierIndex(int minutes) {
  if (minutes <= 25) return 0;
  if (minutes <= 55) return 1;
  if (minutes <= 115) return 2;
  if (minutes <= 175) return 3;
  return 4;
}

class _AppRow extends StatefulWidget {
  const _AppRow({
    required this.app,
    required this.budgetMin,
    required this.onToggle,
    required this.onBudgetChanged,
  });

  final AppInfoDto app;
  final int? budgetMin;
  final VoidCallback onToggle;
  final ValueChanged<int> onBudgetChanged;

  @override
  State<_AppRow> createState() => _AppRowState();
}

class _AppRowState extends State<_AppRow> {
  final _random = Random();
  int? _tierIndex;
  String _copy = '';

  @override
  void initState() {
    super.initState();
    _rollCopyIfTierChanged();
  }

  @override
  void didUpdateWidget(covariant _AppRow oldWidget) {
    super.didUpdateWidget(oldWidget);
    _rollCopyIfTierChanged();
  }

  /// Re-rolls the roast line only when the slider crosses into a new tier,
  /// not on every drag tick within the same tier — otherwise the line
  /// would flicker on every 5-minute step instead of reading as one
  /// stable joke per tier.
  void _rollCopyIfTierChanged() {
    final minutes = widget.budgetMin;
    if (minutes == null) return;
    final tier = _budgetTierIndex(minutes);
    if (tier != _tierIndex) {
      _tierIndex = tier;
      final lines = _budgetTierLines[tier];
      _copy = lines[_random.nextInt(lines.length)];
    }
  }

  @override
  Widget build(BuildContext context) {
    final app = widget.app;
    final budgetMin = widget.budgetMin;
    final onToggle = widget.onToggle;
    final onBudgetChanged = widget.onBudgetChanged;
    final selected = budgetMin != null;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Container(
        decoration: hardShadowBox(
          fill: selected ? BonkedColors.yellow : BonkedColors.paper,
          borderWidth: 2,
          shadowOffset: const Offset(3, 4),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            InkWell(
              onTap: onToggle,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                child: Row(
                  children: [
                    Container(
                      width: 24,
                      height: 24,
                      decoration: BoxDecoration(
                        color: selected ? BonkedColors.ink : Colors.transparent,
                        border: Border.all(color: BonkedColors.ink, width: 2),
                      ),
                      child: selected ? const Icon(Icons.check, size: 16, color: BonkedColors.yellow) : null,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        app.label,
                        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: BonkedColors.ink),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            if (selected && budgetMin != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _formatBudget(budgetMin!),
                      style: const TextStyle(color: BonkedColors.ink, fontWeight: FontWeight.w900, fontSize: 16),
                    ),
                    SliderTheme(
                      data: SliderTheme.of(context).copyWith(
                        activeTrackColor: BonkedColors.ink,
                        inactiveTrackColor: BonkedColors.ink.withValues(alpha: 0.25),
                        thumbColor: BonkedColors.ink,
                        overlayColor: BonkedColors.ink.withValues(alpha: 0.12),
                        valueIndicatorColor: BonkedColors.ink,
                        valueIndicatorTextStyle: const TextStyle(color: BonkedColors.yellow, fontWeight: FontWeight.w800),
                      ),
                      child: Slider(
                        value: budgetMin!.toDouble().clamp(_kBudgetMinMinutes.toDouble(), _kBudgetMaxMinutes.toDouble()),
                        min: _kBudgetMinMinutes.toDouble(),
                        max: _kBudgetMaxMinutes.toDouble(),
                        divisions: (_kBudgetMaxMinutes - _kBudgetMinMinutes) ~/ _kBudgetStepMinutes,
                        label: _formatBudget(budgetMin!),
                        onChanged: (value) => onBudgetChanged(value.round()),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.only(bottom: 2),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(_formatBudget(_kBudgetMinMinutes), style: bonkedBody(size: 11)),
                          Text(_formatBudget(_kBudgetMaxMinutes), style: bonkedBody(size: 11)),
                        ],
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Text(
                        _copy,
                        style: TextStyle(
                          color: _budgetTierColors[_tierIndex!],
                          fontWeight: _budgetTierWeights[_tierIndex!],
                          fontSize: _budgetTierSizes[_tierIndex!],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}
