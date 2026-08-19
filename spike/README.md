# memeapp latency spike

Go/no-go test for the core mechanism: can a UsageEvents-polling foreground
service detect a watched app reaching the foreground and show a full-screen
overlay within the PRD budget (**p90 ≤ 1.5 s**, app-open → roast visible)?

## What it does

- `WatcherService`: foreground service, polls `UsageStatsManager.queryEvents`
  every 500 ms (configurable via intent extra `pollMs`), watches for
  `ACTIVITY_RESUMED` of the packages in `WATCHED`, throws a full-screen
  `TYPE_APPLICATION_OVERLAY` window, and logs three timestamps per event:
  - `detectMs` — event timestamp → poll loop noticed it
  - `addMs` — event timestamp → `WindowManager.addView` returned
  - `drawMs` — event timestamp → overlay's first pre-draw (≈ visible)
  plus running p50/p90 over `drawMs`.
- `MainActivity`: three buttons for the permission grants; auto-starts the
  watcher once usage access + overlay are both granted.

## Results — emulator, 2026-08-19

Pixel 9 Pro AVD (API 35), host under load, n=12 across Settings / Chrome /
Play Store / YouTube, mixed cold and warm launches, 500 ms poll:

| Metric | Result |
|---|---|
| detect (median) | ~400 ms — as designed for a 500 ms poll |
| end-to-end p50 | ~1.17 s |
| end-to-end p90 | ~1.25–1.56 s |
| worst sample | 2.0 s |

**Verdict: provisional GO.** At/under budget on a software-rendered emulator
on a loaded host; real hardware renders this path faster. The binding
measurement must be repeated on real mid-tier devices (per the PRD).

## Key finding (would have shipped as a bug)

Overlay windows added from a service context are **software-rendered by
default**. Without `WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED` on
the window, first-draw latency ballooned to 2–7 s on the emulator and slow
draws back-pressured the poll loop (main thread). With the flag, the
distribution tightened to the numbers above. Keep this flag in the real app.

## Rerun (emulator or real device)

```bash
cd spike
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.memeapp.spike android:get_usage_stats allow
adb shell appops set com.memeapp.spike SYSTEM_ALERT_WINDOW allow
adb shell am start -n com.memeapp.spike/.MainActivity   # auto-starts watcher
# open watched apps a few times, then:
adb logcat -d -s SPIKE | grep ROAST
```

On a real device the two appops commands work over USB debugging; or use the
two grant buttons in the app (Settings deep links).

Watched packages are hardcoded in `WatcherService.WATCHED` — edit to match
what's installed on the test device. Poll interval:
`adb shell am start-foreground-service -n com.memeapp.spike/.WatcherService --el pollMs 250`
(after the activity has started it once; the service is not exported).

## Still to validate on real hardware

- p90 on a mid-tier phone (the actual PRD gate)
- OEM battery-killer behavior (Xiaomi/Samsung/OnePlus) — service survival
  over hours/days, not just latency
- battery cost of the 500 ms poll over a full day
- poll at 250 ms vs 500 ms: latency gain vs battery cost
