package com.arkarizdev.grudge.core.watcher

/**
 * Per-package session lifecycle (tech plan §3). One state machine instance
 * per watched package, driven entirely off wall-clock time — expiry is an
 * absolute timestamp, not a pausable countdown, so leaving and returning to
 * an app doesn't buy extra time.
 *
 *   IDLE --app foregrounded--> INTENT_PENDING
 *   INTENT_PENDING --grant(min, text?)--> RUNNING
 *   RUNNING --now >= expiry--> ROASTING
 *   ROASTING --"I'm done" / app left--> ENDING
 *   ROASTING --extend(tier n)--> RUNNING
 *   ENDING --60s grace, no return--> IDLE   (T-111: outcome classification)
 */
enum class SessionState {
    IDLE,
    INTENT_PENDING,
    RUNNING,
    ROASTING,
    ENDING,
}
