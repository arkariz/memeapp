# Grudge v1 — Product Requirements

**Status:** Draft v2 · **Date:** 2026-08-19 · **Platform:** Android-first
**One-liner:** A usage limiter that captures your intention when you open a distracting app, then roasts you with your own words when you blow past it — and hands you a shareable card when you don't.

---

## Problem Statement

Self-aware doom-scrollers open apps intending "just 5 minutes" and surface 40 minutes later. Existing screen-time tools interrupt with emotionally flat, instantly dismissible walls, so the moment of overuse passes unnoticed — and the tools themselves get uninstalled by week two. The cost of not solving it: users' own stated intentions fail daily, and the intervention that's supposed to help has no emotional grip at the only moment that matters.

**Target user:** self-aware doom-scrollers who install semi-ironically. They opt into being roasted (tone risk is low) but are novelty tourists (retention risk is high). Explicitly *not* for compulsive-use clinical cases or parental control.

**Why Android first:** Android lets Grudge draw its own full-screen overlay at the block moment — the roast can be the actual meme, visually, exactly where the original brief wanted it ("noticeable, unexpected"). iOS restricts that moment to two lines of text on Apple's shield template and gates distribution behind an entitlement approval. Android gives the core moment its full expression and removes the external gatekeeper from the launch path.

## Core Loop

The overlay is the doorway, not just the wall:

1. **Intent capture at open** — Grudge's monitoring service detects a watched app coming to the foreground and covers it with a full-screen overlay: "How long this time?" (duration pick + optional free-text intention, e.g. *"just checking one thing"*). The overlay stands down for exactly the granted time.
2. **Callback roast at expiry** — the overlay returns as a full-screen, meme-formatted roast quoting the user's own words: *"'Just checking one thing.' That was 47 minutes ago."* Copy and layout are precomputed at grant time, so the roast renders instantly and offline.
3. **Negotiation, not prohibition** — more time is always available, at escalating friction (tap → type a phrase → wait timer). Every extension is logged.
4. **Streaks & success cards** — beating your own estimate feeds a streak; streak milestones and beaten estimates generate meme-formatted share cards. **Designed share moments attach to success, not failure** — the roast overlay has no share affordance, ever. It will get screenshotted organically; it is never optimized for sharing.

Why this shape: intent capture gives the humor its material (counters habituation — the user keeps writing the punchline), humor gives sharing its content, streaks give day-30 a reason. And the intervention moves to the doorway (app-open), where sessions are cheapest to stop.

## Goals

1. **Behavior change (north star):** retained users reduce weekly overage minutes ≥25% by day 30 vs. their week-1 baseline.
2. **Loop adoption:** ≥60% of watched app-opens still go through intent capture at day 14 (users aren't routing around the product).
3. **Retention:** ≥20% day-30 retention (hypothesis target; screen-time category is notoriously churn-heavy — validate against beta).
4. **Organic growth (secondary):** ≥8% of success-card views produce a share; shares become a measurable install source.

Goal 1 is primary. When a design decision trades behavior change against shareability, behavior change wins.

## Non-Goals (v1)

- **Social accountability / friends leaderboard** — strongest retention-layer candidate for v2, but adds server infra and privacy scope now. Parked, not rejected.
- **iOS** — parked for v2. Apple's shield is copy-only at the block moment and distribution requires a Family Controls entitlement with weeks of lead time — a worse first home for this product's core moment. Portability insurance: every roast template must define a two-line text degradation so the library survives an iOS port.
- **Accessibility-service-based blocking** — never, not just not-now. Google Play policy restricts accessibility APIs to accessibility use cases, and the privacy overreach contradicts the product's stance. Monitoring is UsageEvents-only.
- **Hard blocking / no-bypass mode** — v1 is a negotiation by design; removing the escape hatch changes the product's contract with the user.
- **AI-generated roasts** — v1 is templates + slots; instant, offline rendering at the block moment is non-negotiable. Server-side prefetched generation is P2.
- **Monetization** — nothing to price before the loop retains.

## User Stories

Ordered by priority. Persona: the doom-scroller.

1. As a doom-scroller, I want to pick which apps Grudge watches and set a daily budget, so the app knows what "too much" means for me.
2. As a doom-scroller opening TikTok, I want to declare how long I'm going in for, so my future self has something to hold me to.
3. As a doom-scroller who hit my declared limit, I want the block screen to quote my own intention back at me, so stopping feels like a punchline instead of a punishment.
4. As a doom-scroller who genuinely needs more time, I want to extend without drama, so the app never forces a choice between it and my real life — even if it gets progressively cheekier about granting it.
5. As a doom-scroller who beat my estimate, I want a shareable card, so my restraint gets an audience.
6. As a doom-scroller on a streak, I want to see the streak and what breaks it, so I have a reason to come back tomorrow.
7. *(Edge)* As a user who skips the free-text intention, I still get duration-based roast copy — the loop never depends on optional input.
8. *(Error)* As a user whose phone killed Grudge's monitoring in the background, I'm told the watch is down and how to fix it — the app never silently pretends to work.

## Requirements

### P0 — cannot ship without

**1. Onboarding & permissions**
- [ ] App picker for watched apps (launcher-intent `<queries>` declaration — no `QUERY_ALL_PACKAGES`); per-app daily budget setting
- [ ] Guided grant flow with plain-language explanations: Usage Access (`PACKAGE_USAGE_STATS`), Display over other apps (`SYSTEM_ALERT_WINDOW`), notifications
- [ ] Battery-optimization exemption requested with an honest explanation of why the watcher needs to stay alive
- [ ] Revoked-permission and killed-service detection with a recovery prompt ("the watch is down")

**2. Monitoring service & intent capture**
- [ ] Foreground service watching UsageEvents; overlay appears within the latency budget (target p90 ≤1.5s of a watched app reaching foreground — validate, see Open Questions)
- [ ] Duration-only path is ≤2 taps; optional intent text capped (~120 chars, driven by card layout)
- [ ] Grant suppresses the overlay for exactly the granted duration; expiry re-triggers it; timers survive service restarts

**3. Roast overlay**
- [ ] Full-screen custom overlay; meme-formatted roast composed at grant time (layout + copy precomputed; instant, offline render)
- [ ] Template library: ≥40 templates across escalation tiers, with slots for intent quote, granted minutes, actual minutes, extension count; every template defines a two-line text degradation (iOS portability insurance)
- [ ] Intent text quoted verbatim when present; duration-based fallbacks otherwise
- [ ] Tone style guide enforced: self-authored teasing, never shame lexicon (no "pathetic," "addict," "wasted your life" register)
- [ ] No share affordance on the roast — sharing is success-side only

**4. Negotiation (extensions)**
- [ ] Extension 1: one tap (+5 min). Extension 2: type a short phrase. Extension 3+: wait timer
- [ ] Every grant and extension logged with timestamps and session context

**5. Streaks & success cards**
- [ ] "Estimate beaten" = session ends at or under granted time with no extension; streak = consecutive days with all sessions beaten
- [ ] Share cards rendered in-app (meme-formatted) for beaten estimates and streak milestones; standard share sheet export
- [ ] Card generation is a success-side event only — no card is ever generated from a roast

**6. Instrumentation & habituation tripwire** *(this is the accepted-risk mitigation — see Risks)*
- [ ] Event schema: intent captures, overlay impressions, first-roast stop rate (session ends ≤2 min after roast with no extension), extension rate, card shares
- [ ] Reliability telemetry: overlay-shown rate per watched open, overlay latency, service uptime, OEM/device model breakdown
- [ ] Weekly cohort comparison built in from day one (week-1 vs week-2 first-roast stop rate per install cohort)
- [ ] Tripwire: if a cohort's first-roast stop rate decays >30% relative from its own week 1 → copy-refresh experiment triggers; if decay survives the refresh → mechanism pivot review (promote friction gradient from negotiation layer to primary intervention)

**7. Privacy**
- [ ] Intent text and usage data never leave the device; analytics events carry no intent content and no app-level usage detail beyond aggregates
- [ ] No account required for the full v1 loop; no accessibility service, ever

### P1 — fast follows

- Weekly report-card meme ("Screen time down 22%. Self-control: still a rumor.") — the strongest share artifact, but the loop works without it
- Remote copy packs: ship new roast templates via remote config without an app release (this is what the tripwire's copy-refresh experiment depends on — build early in P1)
- OEM reliability playbook: in-app, vendor-specific guidance for autostart/battery whitelisting (Xiaomi, Samsung, Huawei, OnePlus), driven by the reliability telemetry
- Tone dial (gentle ↔ brutal)
- Roast-baiting detection refinement (see counter-metrics)

### P2 — architectural insurance

- Server-side LLM-generated roasts, prefetched at grant time into the same precomputed-roast store (P0-3's architecture is designed so this slots in without rework)
- Social layer / friends leaderboard
- iOS port (two-line template degradations already exist; entitlement request would be the long pole — start it a quarter early)
- Habituation-adaptive copy engine (template selection learns from the user's stop-rate history)

## Success Metrics

| Type | Metric | Target | When |
|---|---|---|---|
| Leading | Onboarding completion (install → watcher active, all grants) | ≥70% | Week 1 |
| Leading | Reliability: overlay shown on watched app-opens | ≥95% | Continuous |
| Leading | Intent-capture rate (% watched opens through the prompt) | ≥60% | Day 14 |
| Leading | First-roast stop rate | ≥40% | Weekly cohorts |
| Leading | Success-card share rate | ≥8% of card views | Week 2+ |
| **Lagging (north star)** | **Weekly overage reduction vs. week-1 baseline** | **≥25%** | **Day 30** |
| Lagging | Day-30 retention | ≥20% | Day 30 |
| Counter | Roast-baiting: ≥3 extensions/day sustained ≥3 days, or usage accelerating toward the limit | Monitor; investigate any cluster | Continuous |
| Counter | Extension-rate creep week-over-week | Tripwire input | Continuous |

**Measurement notes:** overage is computed on-device and reported as anonymous aggregates. Known limitation: uninstalled users go dark, so day-30 numbers carry survivor bias — report retention alongside the north star, never the north star alone. Onboarding completion is a riskier target on Android than it looks: the flow needs two special-access grants plus a battery exemption, each a trip into system settings — instrument per-step drop-off.

## Risks

**1. Habituation — accepted risk, by decision (2026-08-19).** The pre-build validation test was deliberately skipped; the thesis that self-authored punchlines resist habituation ships unproven. Mitigation is structural, not hopeful: the P0 instrumentation exists primarily to catch this, the tripwire has numeric thresholds decided now (not post-hoc), remote copy packs are the first response lever, and the friction-gradient pivot is the pre-agreed fallback. **Committed checkpoint: day 14 after production rollout is a decision meeting, not a status update.**

**2. Background reliability — the Android tax.** Aggressive OEM battery management (Xiaomi, Huawei, Samsung, OnePlus) kills background services, and a dead watcher means the product silently does nothing — the worst failure mode for a trust product. Mitigations: foreground service with persistent notification, battery-exemption request in onboarding, uptime telemetry with per-OEM breakdown from day one, and the "watch is down" recovery prompt. The P1 OEM playbook is the follow-up lever.

**3. Overlay latency race.** Between app-open and overlay, the user sees live feed. If detection is slow, intent capture stops feeling like a doorway and starts feeling like an interruption mid-scroll — a different, worse product. The latency budget (p90 ≤1.5s) is a P0 acceptance criterion, not an aspiration; if UsageEvents polling can't hit it on mid-tier devices, that's a Phase-1 stop-and-rethink.

**4. Play Store policy.** `PACKAGE_USAGE_STATS` requires a Play Console permission declaration; digital-wellbeing apps are a permitted category, so this is process risk, not rejection-by-default — but the declaration and review add lead time, and policy shifts around special permissions are a standing exposure. Avoiding the accessibility API entirely (non-goal) removes the biggest policy landmine.

**5. Roast-baiting, elevated.** On Android the roast is a full-screen meme — better at its job, and more collectible. The incentive to blow limits on purpose to see the roast is *stronger* than it was in the iOS design. Held in check by: no share affordance on roasts, escalation tiers that get less rewarding (drier, more effortful) with each extension, and the baiting counter-metric watched from launch.

## Open Questions

| Question | Owner | Blocking? |
|---|---|---|
| Overlay latency: what detection interval can a UsageEvents-polling foreground service sustain on mid-tier devices without meaningful battery cost, and does it hit p90 ≤1.5s? Spike this first | Engineering | **Yes** — validates the core loop's feasibility |
| Stack: Flutter host app + native Kotlin module for the monitoring service and overlay rendering, vs. full native Kotlin. Recommendation: Flutter host given team background, but the overlay view itself renders natively (Flutter-drawn overlays over other apps are fragile) | Engineering | **Yes** |
| Timer integrity: how do grant/expiry timers survive process death, reboots, and Doze without drift users would notice? | Engineering | **Yes** |
| Success-card visual system: original meme formats (no licensing exposure) — what's the v1 set? | Design | No |
| Tone style guide: the concrete lexicon line between roast and shame, written as rules a template reviewer can apply | Product | No |
| Overage baseline handling when a user edits budgets mid-cohort | Data | No |
| Minimum supported Android version and the OEM test matrix (proposal: Android 10+; Pixel, Samsung, Xiaomi as the matrix core) | Engineering | No |

## Timeline & Phasing

- **Now:** draft the Play Console permission declaration for Usage Access; set up the internal testing track; acquire the OEM test matrix devices (a Pixel alone will hide the reliability risk).
- **Phase 1 — core loop:** latency spike first (go/no-go on the detection approach), then onboarding, intent capture, roast overlay, negotiation. Exit: closed beta on the internal track, instrumented, running on all matrix devices.
- **Phase 2 — retention & growth layer:** streaks, success cards, cohort dashboards. Exit: staged production rollout.
- **Phase 3 — data-driven:** P1 items sequenced by what the day-14 checkpoint says; remote copy packs first if the tripwire is anywhere near firing, OEM playbook first if reliability telemetry is ugly.
