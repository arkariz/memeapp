# Grudge v1 — Technical Plan & Task Backlog

**Status:** Draft v1 · **Date:** 2026-08-19 · **Platform:** Android 10+ (minSdk 29)
**Rebrand note (2026-08-21):** product display name changed from "Grudge" to "Bonked — Bro, Put the Phone Down" (launcher label, in-app copy, and the foreground-service notification). Internal identifiers (package `com.arkarizdev.grudge`, class names, log tags) were deliberately left unchanged. This doc keeps its original title/filename; treat "Grudge" in prose below as the pre-rebrand name.
**Companions:** [PRD](memeapp-v1-prd.md) · [Latency spike](../spike/README.md) · [Figma designs](https://www.figma.com/design/oYbpumicFw6I9f94SlqKiS)

---

## 1. Requirements Recap (from PRD)

**Functional:** intent capture at app-open via overlay → precomputed roast overlay at expiry → escalating extensions → streaks and success-side share cards → habituation instrumentation with a numeric tripwire.

**Non-functional:**
- Overlay appears p90 ≤ 1.5 s after a watched app reaches foreground (spike: provisionally met — p50 ~1.17 s / p90 ~1.25–1.56 s on emulator; binding number needs real hardware)
- Roast renders instantly and offline (precompute at grant time; no network on the hot path)
- All intent text and usage data stays on-device; analytics are anonymous aggregates; no account
- Service survives OEM battery management well enough that failure is *detected and surfaced*, never silent

**Constraints:** solo developer with Flutter background; no accessibility API ever (Play policy + product stance); no `QUERY_ALL_PACKAGES` (launcher-intent `<queries>` only).

## 2. Stack Decision

**Flutter host + native Kotlin core module.** Everything time-critical or OS-integrated is Kotlin; everything screen-shaped is Flutter.

| Concern | Where | Why |
|---|---|---|
| Watcher service, UsageEvents polling | Kotlin | Must run without any Flutter engine alive; spike code seeds it directly |
| Intent + roast overlays | Kotlin (native views) | Overlay windows from a service are native territory; Flutter-drawn overlays over other apps are fragile. Spike lesson baked in: **always set `FLAG_HARDWARE_ACCELERATED`** — without it, first draw took 2–7 s |
| Roast engine, session state, Room DB | Kotlin | Written by the service while the Flutter engine may be dead |
| Onboarding, Home, settings, cards/share | Flutter | Team speed; nothing here is latency-critical |
| Bridge | Pigeon (typed codegen) | Hand-rolled channels rot; Pigeon contracts double as documentation |

**Trade-offs accepted:** two languages in one repo and a bridge to maintain — in exchange for Flutter velocity on ~60% of the surface and native guarantees on the 40% that can't compromise. **Escape hatch:** if bridge friction dominates, the fallback is full-native Compose; the Kotlin core is identical in both worlds, so the decision is low-regret and reversible until Phase 2. Confirm after real-device validation (T-001/T-002).

## 3. Architecture

```
┌────────────────── Flutter app (Dart) ──────────────────┐
│  Onboarding flow · Home/streaks · Settings ·           │
│  Card renderer + share sheet · Analytics UI            │
└─────────────────────────┬──────────────────────────────┘
                 Pigeon typed channels
        (queries + event stream; DTOs only, no SQL)
┌─────────────────────────┴────────── Kotlin core ───────┐
│  WatcherService (FGS · 500 ms UsageEvents poll)        │
│   ├── SessionStateMachine (one per watched package)    │
│   ├── OverlayController (intent + roast · HW-accel)    │
│   ├── RoastEngine (templates → precompute at grant)    │
│   └── Heartbeat writer (every poll tick)               │
│  WatchdogWorker (WorkManager, 15 min · restarts +      │
│                  logs kills with OEM tag)              │
│  Room DB: watched_apps · sessions · roast_payloads ·   │
│           streaks · analytics_queue · heartbeat        │
└────────────────────────────────────────────────────────┘
```

**Session state machine (per watched package):**

```
IDLE ──app foregrounded──▶ INTENT_PENDING (intent overlay up)
INTENT_PENDING ──grant(min, text?)──▶ RUNNING (expiry persisted,
                                       roast precomputed NOW)
RUNNING ──poll sees now ≥ expiry──▶ ROASTING (roast overlay up)
ROASTING ──"I'm done" / app left──▶ ENDING
ROASTING ──extend(tier n)──▶ RUNNING (extensions++, next roast
                                      precomputed at new tier)
ENDING ──60 s grace, no return──▶ IDLE (outcome written:
              beaten | overage(seconds) | extended(n))
```

Key properties: the roast payload for a session is **always precomputed at grant/extension time** and stored in Room — the ROASTING transition only reads. Expiry is checked by piggybacking the existing 500 ms poll against the persisted expiry timestamp — **no AlarmManager needed**: a roast is only displayable while the service lives anyway, and on service restart active grants reload from Room. Session end uses a 60 s grace window so app-switch flickers don't award false victories.

## 4. Data Model (Room)

```
watched_app    pkg PK · budget_min · enabled · added_at
session        id PK · pkg · opened_at · intent_text? ·
               granted_min · expiry_at · extensions ·
               ended_at? · outcome? (BEATEN|OVERAGE|ABANDONED) ·
               overage_s
roast_payload  session_id FK · tier · line1 · line2 ·
               asset_ref · created_at        ← read-only hot path
streak         id=1 · current · best · last_counted_day
daily_agg      date+pkg PK · used_min · overage_min   ← analytics
analytics_evt  id PK · name · props_json · created_at · sent_at?
heartbeat      id=1 · last_tick_at · service_started_at
```

Intent text lives only in `session` and `roast_payload`, never in `analytics_evt` (no-PII audit is a task, not a hope).

## 5. Key Flows

**Intent capture (the doorway).** Poll detects `ACTIVITY_RESUMED` for a watched pkg with no RUNNING session → OverlayController shows the intent overlay (native view: duration chips, optional text field — window needs focus, so it's added focusable, unlike the roast). ≤2 taps for duration-only. Grant → overlay dismissed, session RUNNING, roast precomputed. Overlay-shown latency logged per event (the p90 metric ships in production telemetry, not just the spike).

**Roast precompute.** At grant: RoastEngine selects tier (extension count + recent overage history), picks a template (no repeat within last N shown), fills slots (verbatim intent quote, granted/actual minutes), resolves the meme asset ref from the bundled pack, writes `roast_payload`. At expiry the overlay binds the stored payload synchronously — zero computation, zero I/O beyond one indexed read, zero network.

**Watch-down detection.** Service writes `heartbeat.last_tick_at` every poll. Three detectors: (1) Flutter app on-resume compares heartbeat age > 2× poll → red banner + recovery screen; (2) WatchdogWorker every 15 min restarts a dead service and logs `watch_down(reason, oem)`; (3) permission revocation checked on both paths (usage access, overlay). Failure is always *visible* — the product never silently does nothing (PRD risk 2).

**Extensions.** Tier 1: one tap +5 min. Tier 2: typed phrase (native input in roast overlay). Tier 3+: wait timer. Each extension re-precomputes the next roast at the higher tier. All grants/extensions logged with timestamps for the roast-baiting counter-metric.

## 6. Instrumentation & Tripwire

Local-first event queue (`analytics_evt`), flushed opportunistically to the provider (default candidate: PostHog — self-hostable, generous free tier; abstracted behind one interface, decided in T-203).

Core events: `onboarding_step(step, ok)` · `grant(pkg, min, has_intent)` · `roast_shown(tier, latency_ms)` · `roast_outcome(stopped|extended, secs_to_action)` · `extension(n)` · `session_end(outcome, overage_s)` · `card_generated(type)` · `card_shared(type)` · `watch_down(reason, oem, uptime_s)`.

**Tripwire job (P0-6):** weekly computation of first-roast stop rate per install-week cohort. If a cohort decays >30% relative to its own week 1 → flagged in the dashboard (copy-refresh experiment is the response lever; friction-gradient promotion is the pre-agreed pivot). Ships in Phase 2, before public launch — the day-14 checkpoint depends on it.

## 7. Content Pipeline (templates, memes, GIFs)

**Principle: config-shaped from day one, with the config bundled.** v1 ships no remote infrastructure, but even the bundled content is a versioned pack read through the same loader a remote pack will use later — so adding remote costs ~2–3 days, not a refactor.

**Pack format** — one versioned JSON + asset files:

```json
{
  "version": 3,
  "templates": [{
    "id": "t_042", "tier": 2, "requires": ["intent_text"],
    "line1": "\"{intent}\"",
    "line2": "THAT WAS {actual_min} MINUTES AGO.",
    "degrade": ["{intent}", "{actual_min} min. Sure."],
    "asset": "monkey_side_eye", "tone": ["default", "brutal"]
  }],
  "assets": { "monkey_side_eye": { "type": "webp", "file": "monkey.webp", "sha256": "…" } }
}
```

In code: slot grammar, tier semantics, schema versioning. In data: every string, every asset, tone tags, selection weights, the iOS two-line degradations.

**Resolution order** (the entire mechanism):

```
filesDir/packs/<latest valid>/   ← downloaded pack, if any
        ↓ fallback
APK assets/roast_pack_v1/        ← bundled pack, always present
```

RoastEngine loads whichever validates (schema version + asset sha256). A bad download falls back to bundled — the app can never be roast-less.

**Remote side — no backend, no Firebase.** A static manifest (`{ "latest": 4, "url": "…/pack_v4.zip", "sha256": "…" }`) on any static host (GitHub Releases / Cloudflare R2). Daily WorkManager job: check → download when newer → verify hash → atomic pointer swap. Firebase Remote Config adds SDK weight to do what a static file does; skip it.

**Images & GIFs:** prefer animated WebP (smaller, better quality); GIF decodes through the same `ImageDecoder`/`AnimatedImageDrawable` path on minSdk 29, so both work. Flutter renders the same files on the cards side. Validator caps: ~2 MB per asset, ~15 MB per pack.

**v1 content drafted (T-004):** [`assets/roast_pack_v1/roast_pack.json`](../assets/roast_pack_v1/roast_pack.json) — 40 templates (15 tier 1, 13 tier 2, 12 tier 3), each with slots, an iOS two-line `degrade`, and an asset ref; 28/40 render duration-only (no intent text required). Tone lexicon rules are written down in [`assets/roast_pack_v1/TONE_GUIDE.md`](../assets/roast_pack_v1/TONE_GUIDE.md) — the roast-vs-shame test every template must pass, plus the escalation shape (tiers get *drier*, never *meaner*). Asset refs (`side_eye`, `unimpressed_cat`, `confused_math`, `philosoraptor`, `surprised`, `stonks`, `this_is_fine`, `waiting`) are concept placeholders for T-005's original artwork — no third-party template art ships in the production APK.

**Pack GC rule:** `roast_payload` resolves asset paths at grant time, so a mid-session pack swap must not delete files a live payload references — old pack directories are deleted on service start only, never on swap. Display must never be able to fail.

**Why this is product-critical, not hygiene:** the habituation tripwire's first response lever is a copy refresh without an app release (a pack push), and the meme-licensing plan is "ship placeholders in beta, push the commissioned originals (T-005) as a pack." Both depend on this pipeline existing before the day-14 checkpoint — hence T-207 sits in Phase 2, not Phase 3.

### 7c. Live meme GIFs via Tenor (decided 2026-08-19, supersedes bundled-only)

**Bundled illustration is now the offline fallback, not the primary experience.** Primary is a real GIF fetched from Tenor's API. This changes the pipeline but not the precompute principle — a live fetch still can't happen on the roast's hot path, so it moves to grant time, same as everything else in §5.

**Why Tenor:** free API tier, native to the Android ecosystem, straightforward attribution requirement (a small "GIF via Tenor" caption — same visual weight as the existing roast-overlay captions already in the Figma design). GIPHY is a reasonable swap; the provider sits behind one interface so this is a low-regret choice.

**Flow — mirrors the RoastEngine precompute exactly:**

```
GRANT (or extension) →
  RoastEngine picks template → template.asset gives a mood id
  → MemeGifProvider.fetch(mood, contentfilter="high"), budget ~3s
      success → download GIF/MP4 rendition → cache to filesDir,
                write gif_source=TENOR + local path into roast_payload
      timeout / no network / API error → write gif_source=BUNDLED,
                roast_payload points at the existing WebP (§7 pipeline, unchanged)
EXPIRY → roast overlay reads roast_payload → renders whichever source
          is already resolved. Zero network calls at display time.
```

The 3s budget lives inside the *grant* flow, which already has slack — the intent-capture UI is showing a confirmation, not racing the 1.5s roast-latency budget from §1. If Tenor is slow or the phone has no signal, the fallback is silent and instant: the user just sees the mascot instead of a live GIF for that one roast, never a blank frame or a spinner.

**Data model addition** — `roast_payload` gains two fields: `gif_source` (`TENOR` | `BUNDLED`) and `gif_local_path`. `roast_pack.json`'s `moods` map (parallel to `assets`, same keys) carries the Tenor search query seeds — every template's existing `asset` id already doubles as a mood id, so **no per-template changes were needed**.

**Non-negotiable per Tenor's terms (verify exact current wording at integration time — API terms drift):**
- `contentfilter=high` on every search call — general-audience safe search, no exceptions. Ties directly to the tone guide's "never shame, never surprise the user with something inappropriate" spirit.
- Visible attribution in the UI wherever a fetched GIF is shown.
- Call Tenor's share-registration endpoint when a GIF is actually used (their recommended usage-tracking practice).
- API key in build config, not committed to source control.
- Cache locally for the session's lifetime; don't treat the cache as a permanent redistribution store.

**Why this stays out of Phase 1:** the core loop (T-101–T-112) ships and gets beta-tested on the *bundled-only* path first — proving the mechanism works without a network dependency in the mix. Tenor is layered on in Phase 2 once that foundation is solid, so a GIF-fetch bug can never take down the core product, only degrade one roast's visual to the mascot fallback.

## 8. Testing Strategy

- **Unit (Kotlin):** RoastEngine (tier selection, slot filling, no-repeat), SessionStateMachine (every transition + grace window), streak day-boundary logic
- **Instrumented:** overlay latency harness — the spike, promoted to a repeatable test rig; grant-reload-after-process-death
- **Manual OEM protocol:** Pixel / Samsung / Xiaomi matrix — overnight service survival, post-reboot recovery, battery-saver-on behavior; results logged per device in the repo
- **Beta:** closed track, all telemetry live, one week minimum before staged rollout

## 9. Revisit as It Grows

iOS port (two-line template degradations already mandatory per PRD) · server-side LLM roasts prefetched into `roast_payload` (schema already fits) · social layer (first thing that needs a backend — nothing in v1 does).

---

## 10. Task Backlog

Sizes: **S** ≤ 1 day · **M** 2–4 days · **L** ~1 week. Solo-dev estimates. Phase 1 ≈ 5–7 weeks.

### Phase 0 — Gate closure (this week)

| ID | Task | Size | Depends | PRD |
|---|---|---|---|---|
| T-001 | Real-device latency validation: run spike on a physical mid-tier phone, record p50/p90, formal go/no-go | S | — | Risk 3 |
| T-002 | Confirm stack decision (default: Flutter host + Kotlin core) after T-001 | S | T-001 | OQ |
| T-003 | ~~Draft Play Console Usage Access declaration~~ — **done**, see [play-console-usage-access-declaration.md](play-console-usage-access-declaration.md); creating the internal testing track itself still pending (Play Console account action) | S | — | Timeline |
| T-004 | ~~Write roast template pack v1~~ — **done**, see §7 and `assets/roast_pack_v1/` | M | — | P0-3, OQ |
| T-005 | ~~Original meme asset set v1~~ — **brief done**, see [meme asset brief](memeapp-meme-asset-brief.md); production (illustration commission or in-house) still pending | M | — | OQ design |

### Phase 1 — Core loop (exit: closed beta, instrumented, on all matrix devices)

| ID | Task | Size | Depends | PRD |
|---|---|---|---|---|
| T-101 | ~~Repo scaffold: Flutter app + Kotlin core module + Pigeon contracts + CI debug build~~ — **done**, started ahead of T-002's formal sign-off per explicit direction; app/ builds clean (`flutter analyze` 0 issues, test passes, debug APK builds) | M | T-002 | — |
| T-102 | ~~Port spike → WatcherService with SessionStateMachine; heartbeat writer~~ — **done**, 11/11 unit tests pass, verified live on emulator (real heartbeat, real IDLE→INTENT_PENDING transition on Chrome open) | M | T-101 | P0-2 |
| T-103 | ~~Room schema + DAOs + grant-reload-on-restart~~ — **done**, 14/14 unit tests pass, live-verified across a real force-stop + reinstall (grant survived, correctly re-derived ROASTING since its expiry had passed while dead) | M | T-101 | P0-2 |
| T-104 | ~~Intent-capture overlay (native): chips, focusable text input, ≤2-tap grant, latency telemetry~~ — **done**, 15/15 unit tests pass, live-verified end-to-end (real tap → grant → Room persist). Known minor issue: PackageManager app-label lookup falls back to raw package name for some apps (e.g. YouTube) — cosmetic, not blocking | L | T-102 | P0-2 |
| T-105 | ~~RoastEngine: template loader, tier selection, slot filler, precompute-at-grant~~ — **done**, 18/18 unit tests pass, live-verified against the real bundled roast_pack.json (correct template + tier + slot-filled text + asset ref, all written to roast_payload at grant time, linked to the session row) | M | T-103, T-004 | P0-3 |
| T-106 | ~~Roast overlay (native, HW-accelerated, meme asset, **no share affordance**)~~ — **done**, live-verified end-to-end (real mascot image + slot-filled text, done/extend both wired). User caught a live show/dismiss flicker loop during testing; root-caused and fixed in both overlay controllers, see memory for detail | M | T-105 | P0-3 |
| T-107 | ~~Extension tiers: +5 tap / typed phrase / wait timer; full grant logging~~ — **done**, all three tiers live-verified across real 5-minute grant/extend cycles (tier derived from `RoastEngine.tierFor`, same function the roast copy already used, so friction and joke escalate together). Fixed a real bug caught in testing: the roast overlay window was `FLAG_NOT_FOCUSABLE`, so tier 2's typed-phrase EditText could be tapped but could never actually receive keyboard input — the window is now conditionally focusable only for tier 2. Every grant/extend now logged via a dedicated `GrudgeGrantLog` tag (pkg, minutes, extensionsSoFar, intentText presence, sessionId, timestamp) per PRD P0-4 | M | T-106 | P0-4 |
| T-108 | ~~Expiry via poll piggyback + persisted expiry; process-death recovery test~~ — **done**, no code changes needed (the mechanism was already correctly built across T-102/T-103/T-105/T-106/T-107); this task's real deliverable was live-proving the ONE case never explicitly tested before: a session already in ROASTING (roast shown, unresolved) survives a full `am force-stop` and resumes on relaunch with the byte-identical precomputed roast (not regenerated), and an extension count survives the same real process death (verified tier 2 correctly reappeared post-restart). One false alarm during testing: `adb shell run-as ... cat databases/grudge.db` alone reads a stale pre-WAL-checkpoint snapshot (Room runs in WAL mode) and briefly looked like a stale-row bug; pulling `-wal`/`-shm` alongside the main db file resolved it — no code defect, a lesson for future db-state verification in this project | S | T-102, T-103 | P0-2 |
| T-109 | ~~Onboarding in Flutter per Figma: welcome, app picker + budgets, 4 grant steps, revocation recovery~~ — **done**, live-verified end-to-end on a real emulator (real installed-app list, real Usage Access/overlay/notification/battery-exemption system dialogs, real revocation-recovery UI triggered live, correct skip-to-scaffold on relaunch). Built directly from PRD/tech-plan copy, not the Figma OB-0..OB-3 frames — Figma MCP was still rate-limited on the Starter plan when this task started; OB-4/ERR-1/ERR-2 remain unbuilt in Figma too | L | T-101 | P0-1 |
| T-110 | ~~Watch-down detection: heartbeat check, WatchdogWorker, red banner + recovery screen~~ — **done**, live-verified full loop on a real emulator: force-stopped the process, relaunch showed the red "THE WATCH IS DOWN ✗ — tap to fix" banner, tapping it opened the full-screen diagnosis (usage/overlay/background rows, ✗ correctly labeled "KILLED BY BATTERY SAVER"), "RESURRECT THE WATCH" self-healed and the banner cleared on return. WatchdogWorker (WorkManager, 15 min periodic — the platform minimum) confirmed actually registered via `dumpsys jobscheduler`, not just code-reviewed | M | T-102 | P0-1 |
| T-111 | ~~Session-end outcome logic: 60 s grace, BEATEN/OVERAGE determination~~ — **done**, closed a real gap found while implementing: the state machine as previously built had no path to BEATEN at all (RUNNING sessions ignored app-left entirely). Added RUNNING→ENDING on early exit (60s anti-flicker grace, returns resume RUNNING unchanged) and immediate ROASTING→IDLE finalization (no grace — see code comments for why). Session rows now survive as history (endedAt/outcome/overageS UPDATE) instead of being deleted on end. 21/21 unit tests pass (was 15); all three outcomes plus the grace-resume path live-verified on a real emulator with real waits (not mocked clocks) and confirmed via direct SQLite inspection — BEATEN, OVERAGE (×2, with correct overageS), EXTENDED (extension always overrides BEATEN per PRD's "no extension" clause), and a return-within-grace flicker that correctly resumed RUNNING instead of finalizing | M | T-102 | P0-5 |
| T-112 | Closed beta cut + OEM matrix smoke (Pixel, Samsung, Xiaomi): overnight survival, reboot recovery | M | all Phase 1 | Risk 2 |

### Phase 2 — Retention & growth (exit: staged production rollout)

| ID | Task | Size | Depends | PRD |
|---|---|---|---|---|
| T-201 | ~~Streak engine + Home screen (Figma 05, incl. budget bars)~~ — **done**, 9/9 `StreakEngine` unit tests pass (pure day-boundary rollover logic), live-verified end-to-end on emulator: watch up/down banner with real timestamps, zero-state streak copy, budget bars rendering correctly both under and over budget. Fixed a live-caught bug: `FractionallySizedBox` collapsed to a near-invisible sliver at 0% fill instead of a full-width empty bar (missing `width: double.infinity` on the parent) | M | T-111 | P0-5 |
| T-202 | ~~Success/streak card renderer + share sheet (success-side only)~~ — **done**, live-verified with a real BEATEN outcome (real grant → left the app → 60s grace → finalized BEATEN), card rendered with correct stats and shared through the real Android share sheet (RepaintBoundary → PNG → `share_plus`), acknowledge-on-dismiss confirmed persisted across a full process restart (card never re-prompts for the same event). Fixed a live-caught bug: `AspectRatio` reserved the meme box's height even when the image failed to load, leaving a blank gap when no success-pack asset exists yet | M | T-201, T-005 | P0-5 |
| T-203 | ~~Analytics: event queue, provider integration (decide PostHog vs alt), **no-PII audit**~~ — **done**: `analytics_evt` (schema-only since T-103) now has a real writer. Provider = PostHog's raw `batch` REST endpoint via the existing OkHttp client (no SDK — rejected Firebase Analytics for conflicting with the app's no-PII/no-cloud stance; rejected the PostHog SDK for auto-capturing device data beyond the allowlist). New `core.analytics` package: `AnalyticsCore` (single funnel `logEvent`/`logEventFromBridge`, enforces a hardcoded per-event field allowlist — the actual no-PII audit mechanism, not just a review step), `AnalyticsGateway`/`PostHogCaptureClient` (mirrors `GifFetchGateway`/`GiphyMemeGifProvider`'s exact shape), `AnalyticsFlushWorker` (WorkManager periodic, 15 min, mirrors `WatchdogWorker`), `AnalyticsPrefs` (random per-install `distinct_id`, not tied to any account). New Pigeon `logAnalyticsEvent(name, propsJson)` HostApi method bridges the 3 Dart-originated events. All 9 events wired: `grant`/`extension` (`WatcherService.logGrantEvent`), `roast_shown` (`maybeShowRoastOverlay`), `session_end`/`roast_outcome` (`persistActiveSessions`'s drain loop — `roast_outcome` deliberately skipped for BEATEN outcomes, which never show a roast), `watch_down` (`WatchdogWorker.doWork`), `onboarding_step`/`card_generated`/`card_shared` (Dart call sites). 12 new unit tests (mirroring `GifFetchGatewayTest`/`GiphyMemeGifProviderTest`'s fake-provider and pure-function patterns), 77 total passing. **Live-verified on-device** (WAL-aware sqlite pull of `analytics_evt`): `onboarding_step` (×4, mixed grant/skip), `grant`, `extension`, `roast_shown` (×2, tier 1 and 2), `session_end`, `roast_outcome` — all with exactly-allowlisted fields, no PII. `AnalyticsFlushWorker` confirmed registered and running (`flush ok=true` with a blank key — correct no-op, `sentAt` stays null rather than false-marking). **Not live-verified**: `card_generated`/`card_shared` (needs a full BEATEN flow — open a watched app, leave before expiry, wait the 60s grace) and `watch_down` (needs the service heartbeat to go stale without triggering Android's near-instant foreground-service auto-restart, which `am crash`/`am force-stop` don't cleanly produce without root) — both code paths reviewed and covered by `AnalyticsCoreTest`'s allowlist regression test, structurally identical to the 7 confirmed paths | M | T-103 | P0-6, P0-7 |
| T-204 | Cohort dashboard + weekly tripwire computation (first-roast stop rate) | M | T-203 | P0-6 |
| T-205 | Onboarding per-step funnel instrumentation | S | T-203, T-109 | Metrics |
| T-206 | Staged rollout config + **calendar the day-14 checkpoint as a decision meeting** | S | T-204 | Risk 1 |
| T-207 | Remote copy packs: static manifest + WorkManager fetch, hash verify, atomic swap, GC rule (see §7) — **must exist before the day-14 checkpoint** | M | T-105 | P1 |
| T-208 | ~~Tenor integration: `MemeGifProvider` interface, search-by-mood, `contentfilter=high`, API key in build config, share-registration call~~ — **done, provider swapped to GIPHY**: Google fully shut down the Tenor API on 2026-06-30 (discovered during planning, not just closed to new signups — all requests error, even for existing clients), so this shipped against GIPHY instead, exactly the low-regret swap §7c itself anticipated. `MemeGifProvider` interface unchanged. Live-verified end-to-end with a real API key: real search hit (`rating=g`), real GIF downloaded and cached, real `roast_payload` row (`gifSource=GIPHY`), real `onsent` pingback fired. One live-caught bug fixed post-ship: the original 3s combined search+download budget was too tight for two cross-host TLS handshakes (api.giphy.com + media\*.giphy.com) — raised to 6s | M | T-105 | OQ (2026-08-19), **resolved 2026-08-21: GIPHY, not Tenor** |
| T-209 | ~~Grant-time GIF prefetch/cache + `gif_source`/`gif_local_path` on `roast_payload`, silent fallback to bundled WebP on timeout/no-network, Tenor attribution UI~~ — **done** (GIPHY attribution UI, per T-208's provider swap): `GifFetchGateway`/`GifCache` do the grant-time fetch+cache+cleanup (deleted per-session on finalize, swept for orphans on service start); `roast_payload` gained `gifId`/`gifOnSentUrl` alongside `gifSource`/`gifLocalPath` so the "used" pingback fires at actual display time, not fetch time (a precomputed roast can be fetched but never shown if the session ends BEATEN first). GIPHY attribution now uses their own official "Powered by GIPHY" lockup image (sourced 2026-08-24 from the user's GIPHY Brandfolder download, replacing the earlier hand-built text+logo row) — live-verified rendering correctly on a real roast overlay; the previous "logo mark unsourced" blocker is closed. GIF variety per mood — previously a known gap (`search()` always requested the same query with `limit=1`, so a repeat mood returned the same top result nearly every time) — **fixed 2026-08-21**: `SEARCH_RESULT_LIMIT=10` + client-side `randomOrNull()` pick in `GiphyMemeGifProvider`, paired with a new per-mood cooldown in `RoastEngine.eligiblePool()` (avoids the last template id and the last `MOOD_COOLDOWN_SIZE=2` moods shown). Both live-verified: two consecutive tier-1 grants for the same pkg produced different templates with different moods (`t1_17`/`side_eye` → `t1_07`/`unimpressed_cat`). Same pass rewrote the entire roast_pack.json to v2 (48 templates, up from 40, leaner deadpan/self-aware-meta copy) and fixed 14 templates whose `{overage_min}` slot always rendered `0` (precompute happens at grant time with `elapsedMin=grantedMin` by design, so overage is always 0 — see T-105's doc comment); those templates now use `{actual_min}` or drop the number entirely where "over" framing needed it | M | T-208, T-105 | OQ (2026-08-19), **resolved 2026-08-21: GIPHY, not Tenor** |

### Phase 3 — Data-driven (sequenced by day-14 checkpoint data)

| ID | Task | Size | Depends | PRD |
|---|---|---|---|---|
| T-302 | OEM reliability playbook: in-app vendor-specific guidance, driven by watch_down telemetry | M | T-110 | P1 |
| T-303 | Weekly report-card meme | M | T-202 | P1 |
| T-304 | Tone dial (gentle ↔ brutal) | S | T-105 | P1 |
| T-305 | Roast-baiting detector refinement | S | T-204 | Counter-metric |

**Critical path:** T-001 → T-002 → T-101 → T-102 → T-104 → T-106 → T-107 → T-112. The two L-sized tasks (T-104 intent overlay, T-109 onboarding) are the schedule risks; T-004 (template writing) is the one most tempting to defer and the one the product actually lives on — it parallelizes with all engineering and should start first.
