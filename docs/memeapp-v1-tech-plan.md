# memeapp v1 — Technical Plan & Task Backlog

**Status:** Draft v1 · **Date:** 2026-08-19 · **Platform:** Android 10+ (minSdk 29)
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

## 7. Testing Strategy

- **Unit (Kotlin):** RoastEngine (tier selection, slot filling, no-repeat), SessionStateMachine (every transition + grace window), streak day-boundary logic
- **Instrumented:** overlay latency harness — the spike, promoted to a repeatable test rig; grant-reload-after-process-death
- **Manual OEM protocol:** Pixel / Samsung / Xiaomi matrix — overnight service survival, post-reboot recovery, battery-saver-on behavior; results logged per device in the repo
- **Beta:** closed track, all telemetry live, one week minimum before staged rollout

## 8. Revisit as It Grows

iOS port (two-line template degradations already mandatory per PRD) · server-side LLM roasts prefetched into `roast_payload` (schema already fits) · social layer (first thing that needs a backend — nothing in v1 does) · remote copy packs move template JSON from assets to fetched cache (Phase 3, schema unchanged).

---

## 9. Task Backlog

Sizes: **S** ≤ 1 day · **M** 2–4 days · **L** ~1 week. Solo-dev estimates. Phase 1 ≈ 5–7 weeks.

### Phase 0 — Gate closure (this week)

| ID | Task | Size | Depends | PRD |
|---|---|---|---|---|
| T-001 | Real-device latency validation: run spike on a physical mid-tier phone, record p50/p90, formal go/no-go | S | — | Risk 3 |
| T-002 | Confirm stack decision (default: Flutter host + Kotlin core) after T-001 | S | T-001 | OQ |
| T-003 | Draft Play Console Usage Access declaration; create internal testing track | S | — | Timeline |
| T-004 | **Write roast template pack v1**: 40 templates × 3 escalation tiers, each with slots + two-line degradation; tone lexicon rules (roast vs shame) written first | M | — | P0-3, OQ |
| T-005 | Original meme asset set v1 (6–10 images, licensing-clean, meme-format compositions) | M | — | OQ design |

### Phase 1 — Core loop (exit: closed beta, instrumented, on all matrix devices)

| ID | Task | Size | Depends | PRD |
|---|---|---|---|---|
| T-101 | Repo scaffold: Flutter app + Kotlin core module + Pigeon contracts + CI debug build | M | T-002 | — |
| T-102 | Port spike → WatcherService with SessionStateMachine; heartbeat writer | M | T-101 | P0-2 |
| T-103 | Room schema + DAOs + grant-reload-on-restart | M | T-101 | P0-2 |
| T-104 | Intent-capture overlay (native): chips, focusable text input, ≤2-tap grant, latency telemetry | L | T-102 | P0-2 |
| T-105 | RoastEngine: template loader, tier selection, slot filler, precompute-at-grant | M | T-103, T-004 | P0-3 |
| T-106 | Roast overlay (native, HW-accelerated, meme asset, **no share affordance**) | M | T-105 | P0-3 |
| T-107 | Extension tiers: +5 tap / typed phrase / wait timer; full grant logging | M | T-106 | P0-4 |
| T-108 | Expiry via poll piggyback + persisted expiry; process-death recovery test | S | T-102, T-103 | P0-2 |
| T-109 | Onboarding in Flutter per Figma: welcome, app picker + budgets, 4 grant steps, revocation recovery | L | T-101 | P0-1 |
| T-110 | Watch-down detection: heartbeat check, WatchdogWorker, red banner + recovery screen | M | T-102 | P0-1 |
| T-111 | Session-end outcome logic: 60 s grace, BEATEN/OVERAGE determination | M | T-102 | P0-5 |
| T-112 | Closed beta cut + OEM matrix smoke (Pixel, Samsung, Xiaomi): overnight survival, reboot recovery | M | all Phase 1 | Risk 2 |

### Phase 2 — Retention & growth (exit: staged production rollout)

| ID | Task | Size | Depends | PRD |
|---|---|---|---|---|
| T-201 | Streak engine + Home screen (Figma 05, incl. budget bars) | M | T-111 | P0-5 |
| T-202 | Success/streak card renderer + share sheet (success-side only) | M | T-201, T-005 | P0-5 |
| T-203 | Analytics: event queue, provider integration (decide PostHog vs alt), **no-PII audit** | M | T-103 | P0-6, P0-7 |
| T-204 | Cohort dashboard + weekly tripwire computation (first-roast stop rate) | M | T-203 | P0-6 |
| T-205 | Onboarding per-step funnel instrumentation | S | T-203, T-109 | Metrics |
| T-206 | Staged rollout config + **calendar the day-14 checkpoint as a decision meeting** | S | T-204 | Risk 1 |

### Phase 3 — Data-driven (sequenced by day-14 checkpoint data)

| ID | Task | Size | Depends | PRD |
|---|---|---|---|---|
| T-301 | Remote copy packs: remote config JSON + asset cache (first lever if tripwire fires) | M | T-105 | P1 |
| T-302 | OEM reliability playbook: in-app vendor-specific guidance, driven by watch_down telemetry | M | T-110 | P1 |
| T-303 | Weekly report-card meme | M | T-202 | P1 |
| T-304 | Tone dial (gentle ↔ brutal) | S | T-105 | P1 |
| T-305 | Roast-baiting detector refinement | S | T-204 | Counter-metric |

**Critical path:** T-001 → T-002 → T-101 → T-102 → T-104 → T-106 → T-107 → T-112. The two L-sized tasks (T-104 intent overlay, T-109 onboarding) are the schedule risks; T-004 (template writing) is the one most tempting to defer and the one the product actually lives on — it parallelizes with all engineering and should start first.
