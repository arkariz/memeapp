package com.arkarizdev.grudge.core.watcher

import android.util.Log

/**
 * Drives per-package session state (see SessionState.kt for the diagram).
 * Deliberately has no Android Service/Context dependency and no Flutter
 * imports — it's plain logic, unit-testable on the JVM, called by
 * WatcherService on every poll tick and on every foreground-app change.
 *
 * Scope note: this owns the STATE TRANSITIONS only. It does not show
 * overlays (T-104/T-106), generate roast copy (T-105), persist sessions
 * across process death (T-103/T-108), or classify final outcomes with a
 * grace window (T-111) — those are separate tasks that will observe this
 * machine's transitions once they exist. For now, transitions are logged
 * so the machine's behavior is verifiable without that UI.
 */
class SessionStateMachine {
    companion object {
        private const val TAG = "GrudgeSessionSM"
    }

    private val sessions = mutableMapOf<String, Session>()

    /** Read-only snapshot for status reporting / tests. */
    fun snapshot(pkg: String): Session? = sessions[pkg]?.copy()

    /** Read-only snapshot of every non-IDLE session — what T-103 persists each tick. */
    fun allActiveSessions(): List<Session> =
        sessions.values.filter { it.state != SessionState.IDLE }.map { it.copy() }

    fun activeSessionCount(): Int =
        sessions.values.count { it.state != SessionState.IDLE }

    /**
     * Seeds the machine from persisted state at service startup (T-103's
     * "grant-reload-on-restart"). Only RUNNING is restorable — a session
     * only gets a Room row once it has a real grant (see grant() below),
     * so anything reloaded here always has grantedMin/expiryAt set. If
     * expiry already passed while the service was dead, the very next
     * tick() naturally re-derives ROASTING — no special-casing needed.
     */
    fun restore(persisted: List<Session>) {
        for (session in persisted) {
            sessions[session.pkg] = session.copy(state = SessionState.RUNNING)
            Log.i(TAG, "pkg=${session.pkg} restored RUNNING expiryAt=${session.expiryAt}")
        }
    }

    /** Called when [pkg] (a watched package) comes to the foreground. */
    fun onAppForegrounded(pkg: String, now: Long) {
        val session = sessions.getOrPut(pkg) { Session(pkg) }
        if (session.state != SessionState.IDLE) return // already mid-session, no-op
        session.state = SessionState.INTENT_PENDING
        Log.i(TAG, "pkg=$pkg IDLE -> INTENT_PENDING")
        // TODO(T-104): show the intent-capture overlay here.
    }

    /**
     * Called when the currently-foreground package changes to something
     * other than [pkg], while [pkg] was ROASTING — the "app left" edge in
     * the diagram. RUNNING sessions are untouched: expiry is wall-clock,
     * so leaving before the limit doesn't need special handling.
     */
    fun onAppLeft(pkg: String, now: Long) {
        val session = sessions[pkg] ?: return
        if (session.state != SessionState.ROASTING) return
        transitionToEnding(session, now)
    }

    /** INTENT_PENDING -> RUNNING. Rejects grants for sessions not awaiting one. */
    fun grant(pkg: String, minutes: Int, intentText: String?, now: Long): Boolean {
        val session = sessions[pkg] ?: return false
        if (session.state != SessionState.INTENT_PENDING) return false
        session.grantedMin = minutes
        session.intentText = intentText
        session.expiryAt = now + minutes * 60_000L
        session.state = SessionState.RUNNING
        Log.i(TAG, "pkg=$pkg INTENT_PENDING -> RUNNING grantedMin=$minutes expiryAt=${session.expiryAt}")
        // TODO(T-105): precompute the roast payload for this grant now.
        return true
    }

    /** ROASTING -> RUNNING with a fresh expiry. Rejects extends outside ROASTING. */
    fun extend(pkg: String, additionalMinutes: Int, now: Long): Boolean {
        val session = sessions[pkg] ?: return false
        if (session.state != SessionState.ROASTING) return false
        session.extensions += 1
        session.expiryAt = now + additionalMinutes * 60_000L
        session.state = SessionState.RUNNING
        Log.i(TAG, "pkg=$pkg ROASTING -> RUNNING extension #${session.extensions} expiryAt=${session.expiryAt}")
        // TODO(T-105/T-107): precompute the next-tier roast; full grant logging.
        return true
    }

    /** ROASTING -> ENDING, triggered by the user tapping "I'm done." */
    fun markDone(pkg: String, now: Long): Boolean {
        val session = sessions[pkg] ?: return false
        if (session.state != SessionState.ROASTING) return false
        transitionToEnding(session, now)
        return true
    }

    /**
     * Advance time-driven transitions. Call once per poll tick.
     * RUNNING -> ROASTING when expiry has passed; ENDING -> IDLE once the
     * (currently zero-length — see TODO) grace window elapses.
     */
    fun tick(now: Long) {
        for (session in sessions.values) {
            when (session.state) {
                SessionState.RUNNING -> {
                    val expiryAt = session.expiryAt ?: continue
                    if (now >= expiryAt) {
                        session.state = SessionState.ROASTING
                        Log.i(TAG, "pkg=${session.pkg} RUNNING -> ROASTING (expired)")
                        // TODO(T-106): show the roast overlay here.
                    }
                }
                SessionState.ENDING -> {
                    // TODO(T-111): real 60s grace window + BEATEN/OVERAGE/EXTENDED
                    // outcome classification. For now, end immediately so the
                    // machine doesn't get stuck — this task only proves the
                    // transition shape, not the final outcome logic.
                    session.state = SessionState.IDLE
                    session.grantedMin = null
                    session.expiryAt = null
                    session.intentText = null
                    session.extensions = 0
                    session.endingSince = null
                    Log.i(TAG, "pkg=${session.pkg} ENDING -> IDLE")
                }
                else -> Unit
            }
        }
    }

    private fun transitionToEnding(session: Session, now: Long) {
        session.state = SessionState.ENDING
        session.endingSince = now
        Log.i(TAG, "pkg=${session.pkg} ROASTING -> ENDING")
    }
}
