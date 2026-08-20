import 'package:flutter/material.dart';

import '../core/watcher_repository.dart';
import '../pigeon/watcher_api.g.dart';
import '../theme/grudge_theme.dart';
import 'permission_flow.dart';

/// OB-1: app picker + per-app daily budget. PRD P0-1: "App picker for
/// watched apps ... per-app daily budget setting." Data source is
/// WatcherRepository.getLaunchableApps (launcher-intent query, never
/// QUERY_ALL_PACKAGES — see WatcherCore.getLaunchableApps's own doc
/// comment). Pre-selects whatever's already in watched_app so re-entering
/// onboarding (e.g. from a future revocation-recovery flow) doesn't wipe a
/// prior selection.
class AppPickerScreen extends StatefulWidget {
  const AppPickerScreen({super.key, required this.repo});

  final WatcherRepository repo;

  @override
  State<AppPickerScreen> createState() => _AppPickerScreenState();
}

class _AppPickerScreenState extends State<AppPickerScreen> {
  static const _budgetOptions = [5, 10, 15, 30];
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
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => PermissionFlow(repo: widget.repo)));
  }

  @override
  Widget build(BuildContext context) {
    final selectedCount = _selectedBudgets.length;
    return Scaffold(
      backgroundColor: GrudgeColors.paper,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('PICK YOUR\nPOISON.', style: grudgeHeadline(size: 28)),
                  const SizedBox(height: 8),
                  Text(
                    'Which apps eat your evenings? Daily budget next to each.',
                    style: grudgeBody(),
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
                  ? const Center(child: CircularProgressIndicator(color: GrudgeColors.ink))
                  : ListView.builder(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      itemCount: _filtered.length,
                      itemBuilder: (context, index) => _AppRow(
                        app: _filtered[index],
                        budgetMin: _selectedBudgets[_filtered[index].pkg],
                        budgetOptions: _budgetOptions,
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
              child: GrudgeDarkButton(
                label: selectedCount == 0 ? 'PICK ONE. YOU KNOW THE ONE.' : 'LOCK IT IN ($selectedCount picked)',
                onPressed: selectedCount == 0 ? null : _continue,
              ),
            ),
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: Text(
                'Budgets are editable. The judgment is not.',
                textAlign: TextAlign.center,
                style: grudgeBody(size: 13),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AppRow extends StatelessWidget {
  const _AppRow({
    required this.app,
    required this.budgetMin,
    required this.budgetOptions,
    required this.onToggle,
    required this.onBudgetChanged,
  });

  final AppInfoDto app;
  final int? budgetMin;
  final List<int> budgetOptions;
  final VoidCallback onToggle;
  final ValueChanged<int> onBudgetChanged;

  @override
  Widget build(BuildContext context) {
    final selected = budgetMin != null;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Container(
        decoration: hardShadowBox(
          fill: selected ? GrudgeColors.yellow : GrudgeColors.paper,
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
                        color: selected ? GrudgeColors.ink : Colors.transparent,
                        border: Border.all(color: GrudgeColors.ink, width: 2),
                      ),
                      child: selected ? const Icon(Icons.check, size: 16, color: GrudgeColors.yellow) : null,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        app.label,
                        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: GrudgeColors.ink),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            if (selected)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
                child: Row(
                  children: budgetOptions
                      .map(
                        (m) => Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: GestureDetector(
                            onTap: () => onBudgetChanged(m),
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                              decoration: BoxDecoration(
                                color: budgetMin == m ? GrudgeColors.ink : Colors.transparent,
                                border: Border.all(color: GrudgeColors.ink, width: 2),
                              ),
                              child: Text(
                                '${m}M',
                                style: TextStyle(
                                  color: budgetMin == m ? GrudgeColors.yellow : GrudgeColors.ink,
                                  fontWeight: FontWeight.w800,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ),
                        ),
                      )
                      .toList(),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
