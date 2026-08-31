// Smoke test — boots the app with a fake WatcherRepository (no real
// platform channel available in a widget test) and verifies the first
// screen renders. Demonstrates the exact testability WatcherRepository
// exists for: a real repo's isOnboardingComplete() would hit a
// MethodChannel with no plugin registered and never resolve.

import 'package:flutter_test/flutter_test.dart';

import 'package:bonked/main.dart';
import 'package:bonked/src/core/watcher_repository.dart';

class _FakeWatcherRepository extends WatcherRepository {
  @override
  Future<bool> isOnboardingComplete() async => false;
}

void main() {
  testWidgets('App boots and shows the welcome screen on first run', (WidgetTester tester) async {
    await tester.pumpWidget(BonkedApp(repo: _FakeWatcherRepository()));
    await tester.pumpAndSettle();

    expect(find.text("LET'S GO"), findsOneWidget);
  });
}
