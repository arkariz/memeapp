# Play Console — Usage Access Permission Declaration (T-003)

**Status:** Draft, ready to submit once the app is built · **Date:** 2026-08-19
**Where this goes:** Play Console → App content → Permissions declaration form (appears when the APK requests `PACKAGE_USAGE_STATS`)

---

## Why this exists

`PACKAGE_USAGE_STATS` is a restricted permission — Google requires an explicit declaration justifying its use before a release with this permission clears review. Digital-wellbeing/usage-limiter apps are an explicitly permitted category for this permission, so this is a process step (fill the form correctly, expect review lead time), not a rejection risk in itself — but a vague or generic justification is the single most common cause of an avoidable back-and-forth with the review team. This doc exists so that back-and-forth doesn't happen.

## Core functionality description

*(This is the top-level "what does your app do" field — keep it factual and centered on the permission's use, not marketing copy.)*

> memeapp is a personal screen-time limiter. Users choose which apps to watch and set a daily time budget for each. The app uses Usage Access solely to detect when a watched app is opened and to measure how long the user has been in it, so the app can show an on-device reminder when the user's own limit is reached. All usage data stays on the device — it is never uploaded, sold, or shared with third parties, and no account is required to use the app.

## Justification for `PACKAGE_USAGE_STATS`

*(This is the field reviewers weigh most heavily — be specific about the mechanism, not just the goal.)*

> The app's core function — helping a user notice and stop overusing an app they've chosen to limit — is not possible without knowing, in real time, which app is currently in the foreground and how long the user has been in it. We use `UsageStatsManager.queryEvents()` to detect `ACTIVITY_RESUMED` events for the specific packages the user has opted to watch. This is polled locally on-device; no usage data leaves the device, and we do not read usage data for apps the user hasn't explicitly selected to monitor. There is no alternative public API that provides foreground-app detection at the reliability this feature requires — Accessibility Services are not used and are not an appropriate substitute, as their intended purpose is assistive technology, not usage monitoring, and using them for this would be a policy violation in the other direction.

## Screen-recording / demo requirement

Google's form requires a short video demonstrating the permission's use in-context. Record this once the app is buildable (ties to T-101+):

- Show the onboarding screen requesting Usage Access, with the plain-language explanation visible (matches the Figma "I NEED TO SEE YOUR SCREEN TIME" screen).
- Show opening a watched app, using it past the set limit, and the app's own limiter UI appearing as a result.
- Keep it under 2 minutes, no narration needed — the on-screen flow should speak for itself.

## Data safety section (separate Play Console form, same submission)

Declare in the Data Safety questionnaire:
- **Data collected:** App usage data (which apps opened, for how long) — collected, not shared with third parties, processed on-device.
- **Data NOT collected:** No personal identifiers, no account data (v1 has no account), no location, no contacts.
- Note for T-208/T-209 (Tenor integration, Phase 2): once live, this section needs a second pass — the mood string sent to Tenor's search API is not personal usage data, but the Data Safety form should still list "network communication with a third-party GIF provider" as a disclosed data flow when that ships. Revisit this doc at T-208.

## What NOT to do (common rejection triggers, avoid by design)

- Don't request `QUERY_ALL_PACKAGES` — the app only ever needs the specific watched packages, declared via `<queries>` in the manifest (already the case in the spike/production manifest).
- Don't request Usage Access broader than the stated purpose — the declaration above should match exactly what the code does, nothing more.
- Don't bundle Usage Access with any ad SDK or analytics SDK that could plausibly repurpose the data — if T-203's analytics provider is added later, the Data Safety form must be updated to reflect it, and usage-event data specifically must stay excluded from anything sent to that provider (this is also the PRD's own no-PII requirement, P0-7).

## Timeline note

File this declaration when the internal testing track is created (aligns with the "Now" line in the tech plan's timeline — this can happen in parallel with Phase 1 engineering, it doesn't block development, only the first release to a track beyond internal testing).
