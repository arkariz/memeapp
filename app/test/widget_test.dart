// Smoke test for the T-101 scaffold — verifies the app boots and the
// Pigeon-check screen renders. Replace once real onboarding UI (T-109)
// lands and there's an actual first screen to test.

import 'package:flutter_test/flutter_test.dart';

import 'package:grudge/main.dart';

void main() {
  testWidgets('App boots and shows the scaffold check page', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const GrudgeApp());

    expect(find.text('Grudge — T-103 scaffold check'), findsOneWidget);
    expect(find.text('Query WatcherCore via Pigeon'), findsOneWidget);
  });
}
