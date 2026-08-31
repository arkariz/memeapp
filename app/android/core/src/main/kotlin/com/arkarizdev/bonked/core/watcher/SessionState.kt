package com.arkarizdev.bonked.core.watcher

/**
 * Per-package session lifecycle (tech plan §3). One state machine instance
 * per watched package, driven entirely off wall-clock time — expiry is an
 * absolute timestamp, not a pausable countdown, so leaving and returning to
 * an app doesn't buy extra time.
 *
 *   IDLE --app foregrounded--> INTENT_PENDING
 *   INTENT_PENDING --grant(min, text?)--> RUNNING
 *   RUNNING --now >= expiry--> ROASTING
 *   RUNNING --app left (before expiry)--> ENDING
 *   ENDING --returns within 60s grace--> RUNNING (resumed, unchanged expiry)
 *   ENDING --60s grace, no return--> IDLE (T-111: outcome = BEATEN, or
 *           EXTENDED if extensions > 0 — PRD P0-5's "no extension" clause)
 *   ROASTING --extend(tier n)--> RUNNING
 *   ROASTING --"I'm done" / app left--> IDLE directly, no grace (T-111:
 *           outcome = OVERAGE or EXTENDED — the deadline's already passed,
 *           there's nothing left to debounce; ENDING's grace window exists
 *           specifically to protect BEATEN from app-switch flicker, per the
 *           tech plan's §3 note — it has no equivalent purpose here)
 */
enum class SessionState {
    IDLE,
    INTENT_PENDING,
    RUNNING,
    ROASTING,
    ENDING,
}
