# Play Console — Usage Access Permission Declaration (T-003)

**Status:** Draft, ready to submit once the Play Console account/track exists · **Date:** 2026-08-19, updated 2026-08-24 for the Bonked rebrand + GIPHY provider swap
**Where this goes:** Play Console → App content → Permissions declaration form (appears when the APK requests `PACKAGE_USAGE_STATS`)

---

## Why this exists

`PACKAGE_USAGE_STATS` is a restricted permission — Google requires an explicit declaration justifying its use before a release with this permission clears review. Digital-wellbeing/usage-limiter apps are an explicitly permitted category for this permission, so this is a process step (fill the form correctly, expect review lead time), not a rejection risk in itself — but a vague or generic justification is the single most common cause of an avoidable back-and-forth with the review team. This doc exists so that back-and-forth doesn't happen.

## Core functionality description

*(This is the top-level "what does your app do" field — keep it factual and centered on the permission's use, not marketing copy.)*

> Bonked is a personal screen-time limiter. Users choose which apps to watch and set a daily time budget for each. The app uses Usage Access solely to detect when a watched app is opened and to measure how long the user has been in it, so the app can show an on-device reminder when the user's own limit is reached. All usage data stays on the device — it is never uploaded, sold, or shared with third parties, and no account is required to use the app.

## Justification for `PACKAGE_USAGE_STATS`

*(This is the field reviewers weigh most heavily — be specific about the mechanism, not just the goal.)*

> The app's core function — helping a user notice and stop overusing an app they've chosen to limit — is not possible without knowing, in real time, which app is currently in the foreground and how long the user has been in it. We use `UsageStatsManager.queryEvents()` to detect `ACTIVITY_RESUMED` events for the specific packages the user has opted to watch. This is polled locally on-device; no usage data leaves the device, and we do not read usage data for apps the user hasn't explicitly selected to monitor. There is no alternative public API that provides foreground-app detection at the reliability this feature requires — Accessibility Services are not used and are not an appropriate substitute, as their intended purpose is assistive technology, not usage monitoring, and using them for this would be a policy violation in the other direction.

## Screen-recording / demo requirement

Google's form requires a short video demonstrating the permission's use in-context. Record this once the app is buildable (ties to T-101+):

- Show the onboarding screen requesting Usage Access, with the plain-language explanation visible (matches the Figma "I NEED TO SEE YOUR SCREEN TIME" screen).
- Show opening a watched app, using it past the set limit, and the app's own limiter UI appearing as a result.
- Keep it under 2 minutes, no narration needed — the on-screen flow should speak for itself.

## Data safety section (separate Play Console form, same submission)

**Status: content below is ready to transcribe into the Play Console Data Safety questionnaire (App content → Data safety). This doc cannot submit the form itself — that's a Play Console account action requiring the developer's own login.**

Data types to declare as **collected**:
- **App activity → App interactions**: which of the user's chosen watched apps were opened and for how long. Purpose: "App functionality" only (the core limiter feature — it's the whole product). Processed on-device via `UsageStatsManager`; **not shared with any third party**. Not tied to identity — no account exists to tie it to.
- **App activity → In-app search history**: NOT applicable — no in-app search.

Data types to declare as **collected AND shared with a third party** (new since the GIPHY integration, T-208/T-209):
- **A short text query derived from which roast "mood" is showing** (e.g. "side eye", "stonks") is sent to GIPHY's public search API (`api.giphy.com`) to fetch a reaction GIF. This is **not** the user's usage data and **not** their typed intent text — it's one of ~8 fixed mood-label strings bundled in the app, chosen by which roast template fired. Purpose: "App functionality". Third party: GIPHY (a GIF search/hosting service). Transmitted over HTTPS. Declare this under "App activity" or "Other" per whichever category the Play Console form's current taxonomy offers for "search query sent to third-party API" — the form's exact categories change over time, so check the live form rather than assuming this doc's wording maps 1:1.
- The GIF's own analytics pingback URL (`analytics.onsent.url`, GIPHY's own required "content was displayed" callback) is also fired — this is GIPHY tracking their own content's usage, not data Bonked collects about the user beyond the mood string already declared above.

Data types to declare as **NOT collected**:
- No personal identifiers (name, email, phone, user ID) — the user's Google account email is visible to Claude/this dev tooling only in the sense that this repo's owner is `muhammadrisky1401@gmail.com`; the **app itself** has no account system and collects none.
- No location, no contacts, no financial info, no health info, no photos/videos/audio, no messages.
- The user's typed "intent text" (why they're opening the app — e.g. "just checking one thing") stays 100% on-device, is never transmitted anywhere including to GIPHY, and is used only to fill a template slot in the locally-rendered roast text.

Security practices to declare:
- Data is encrypted in transit (HTTPS to GIPHY; nothing else leaves the device).
- No data deletion request mechanism needed to build — there's no server-side data to delete (everything the app stores lives in the device's local Room database, removed automatically on app uninstall).
- Independent security review: not conducted (solo-dev project, N/A — answer "No" rather than leaving blank).

## What NOT to do (common rejection triggers, avoid by design)

- Don't request `QUERY_ALL_PACKAGES` — the app only ever needs the specific watched packages, declared via `<queries>` in the manifest (already the case in the spike/production manifest).
- Don't request Usage Access broader than the stated purpose — the declaration above should match exactly what the code does, nothing more.
- Don't bundle Usage Access with any ad SDK or analytics SDK that could plausibly repurpose the data — if T-203's analytics provider is added later, the Data Safety form must be updated to reflect it, and usage-event data specifically must stay excluded from anything sent to that provider (this is also the PRD's own no-PII requirement, P0-7).

## Timeline note

File this declaration when the internal testing track is created (aligns with the "Now" line in the tech plan's timeline — this can happen in parallel with Phase 1 engineering, it doesn't block development, only the first release to a track beyond internal testing).

## Status as of 2026-08-24 (release-readiness pass)

- ✅ Release signing config wired (`app/android/app/build.gradle.kts` reads `key.properties`; keystore generation is a manual step the developer runs themselves — see the keytool command in the release checklist).
- ✅ Custom app icon shipped (was the stock Flutter template logo until this pass — `assets/app_icon/` has the source SVGs, adaptive icon wired for API 26+, legacy mipmaps for older devices, 512×512 Play Store listing icon at `assets/app_icon/playstore_icon_512.png`).
- ⬜ GIPHY attribution logo (`app/android/core/src/main/assets/giphy/attribution_badge.png`) still not sourced — requires a human login to GIPHY's Brandfolder, see that folder's README for the exact link.
- ⬜ Play Console account + internal testing track: not created.
- ⬜ Public privacy policy URL: not written/hosted.
- ⬜ Store listing assets (screenshots, feature graphic, short/long description, content rating questionnaire): not started.
- ⬜ Trademark/name-conflict check for "Bonked — Bro, Put the Phone Down": not yet done as a formal search (informal spot-check only — see any accompanying note from that pass).
