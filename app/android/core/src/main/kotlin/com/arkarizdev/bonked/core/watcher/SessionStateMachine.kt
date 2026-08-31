package com.arkarizdev.bonked.core.watcher

import android.util.Log

/**
 * Drives per-package session state (see SessionState.kt for the diagram).
 * Deliberately has no Android Service/Context dependency and no Flutter
 * imports — it's plain logic, unit-testable on the JVM, called by
 * WatcherService on every poll tick and on every foreground-app change.
 *
 * Scope note: this owns the STATE TRANSITIONS only. It does not show
 * overlays (T-104/T-106), generate roast copy (T-105), or persist sessions
 * across process death (T-103/T-108) — those observe this machine's
 * transitions. T-111 (60s grace + BEATEN/OVERAGE/EXTENDED) IS implemented
 * here, via [drainFinishedSessions] — WatcherService is responsible for
 * writing those to Room, not for computing them.
 */
class SessionStateMachine {
    companion object {
        private const val TAG = "BonkedSessionSM"

        /**
         * Anti-flicker window for the RUNNING->ENDING early-exit path only
         * — see SessionState.kt's diagram comment for why the ROASTING
         * exit path doesn't use this at all.
         */
        private const val GRACE_MS = 60_000L
    }

    private val sessions = mutableMapOf<String, Session>()
    private val finishedSessions = mutableListOf<FinishedSession>()

    /** Read-only snapshot for status reporting / tests. */
    fun snapshot(pkg: String): Session? = sessions[pkg]?.copy()

    /** Read-only snapshot of every non-IDLE session — what T-103 persists each tick. */
    fun allActiveSessions(): List<Session> =
        sessions.values.filter { it.state != SessionState.IDLE }.map { it.copy() }

    fun activeSessionCount(): Int =
        sessions.values.count { it.state != SessionState.IDLE }

    /**
     * T-111: every session that finalized (BEATEN/OVERAGE/EXTENDED) since
     * the last drain. WatcherService calls this every time it syncs to
     * Room and writes each entry as a Room UPDATE (endedAt/outcome/
     * overageS) rather than the old delete-on-end behavior.
     */
    fun drainFinishedSessions(): List<FinishedSession> {
        val result = finishedSessions.toList()
        finishedSessions.clear()
        return result
    }

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
        when (session.state) {
            SessionState.IDLE -> {
                session.state = SessionState.INTENT_PENDING
                Log.i(TAG, "pkg=$pkg IDLE -> INTENT_PENDING")
                // TODO(T-104): show the intent-capture overlay here.
            }
            SessionState.ENDING -> {
                // Returned within the grace window: this was a flicker,
                // not a real end. Resume exactly where they left off —
                // same expiry, same extensions, clock never paused.
                session.state = SessionState.RUNNING
                session.endingSince = null
                Log.i(TAG, "pkg=$pkg ENDING -> RUNNING (returned within grace)")
            }
            else -> Unit // already mid-session, no-op
        }
    }

    /**
     * Called when the currently-foreground package changes to something
     * other than [pkg]. Three cases matter:
     *  - RUNNING -> ENDING: the T-111 early-exit path. Starts the 60s
     *    grace window; if they don't come back, this finalizes as BEATEN
     *    (or EXTENDED, if they'd already extended at least once).
     *  - ROASTING -> finalize immediately (OVERAGE or EXTENDED, no
     *    grace) — see SessionState.kt's diagram comment for why.
     *  - INTENT_PENDING -> IDLE: the user backed out of the intent-capture
     *    overlay (T-104) without granting. There's no session to "end" —
     *    nothing was ever agreed to — so this resets cleanly rather than
     *    finalizing an outcome, and critically un-sticks the package so
     *    onAppForegrounded() will show the overlay again next time.
     */
    fun onAppLeft(pkg: String, now: Long) {
        val session = sessions[pkg] ?: return
        when (session.state) {
            SessionState.RUNNING -> {
                session.state = SessionState.ENDING
                session.endingSince = now
                Log.i(TAG, "pkg=$pkg RUNNING -> ENDING (grace started)")
            }
            SessionState.ROASTING -> finalizeSession(session, now, exceededBudget = true, roastShown = true)
            SessionState.INTENT_PENDING -> {
                session.state = SessionState.IDLE
                Log.i(TAG, "pkg=$pkg INTENT_PENDING -> IDLE (abandoned, no grant)")
            }
            else -> Unit
        }
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
        return true
    }

    /**
     * ROASTING -> IDLE immediately, triggered by the user tapping "I'm
     * done." No grace window (see SessionState.kt) — an explicit tap is
     * unambiguous, unlike an app-switch.
     *
     * exceededBudget = false: reaching ROASTING always means the granted
     * time is technically up, but finalizeSession already treats any
     * extension as disqualifying (session.extensions > 0 -> EXTENDED,
     * checked before exceededBudget). So for a session that was never
     * extended, an explicit "I'm done" tap is the same voluntary stop as
     * the RUNNING early-exit path — it should finalize BEATEN, not
     * OVERAGE, and be eligible for the success card.
     */
    fun markDone(pkg: String, now: Long): Boolean {
        val session = sessions[pkg] ?: return false
        if (session.state != SessionState.ROASTING) return false
        finalizeSession(session, now, exceededBudget = false, roastShown = true)
        return true
    }

    /**
     * Advance time-driven transitions. Call once per poll tick.
     * RUNNING -> ROASTING when expiry has passed; ENDING -> IDLE once the
     * 60s grace window elapses with no return.
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
                    val since = session.endingSince ?: continue
                    if (now - since >= GRACE_MS) {
                        // Reached ENDING only via the RUNNING early-exit path
                        // (see onAppLeft) — they left before expiry, and the
                        // grace window just confirmed it wasn't a flicker.
                        finalizeSession(session, now, exceededBudget = false, roastShown = false)
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * The single place BEATEN/OVERAGE/EXTENDED gets decided (T-111 / PRD
     * P0-5) — extensions always wins regardless of [exceededBudget],
     * matching "estimate beaten ... with no extension" literally: once
     * you've asked for more time, you're not eligible for beaten even if
     * you end up finishing early against the new deadline.
     */
    private fun finalizeSession(session: Session, now: Long, exceededBudget: Boolean, roastShown: Boolean) {
        val outcome = when {
            session.extensions > 0 -> SessionOutcome.EXTENDED
            exceededBudget -> SessionOutcome.OVERAGE
            else -> SessionOutcome.BEATEN
        }
        val overageS = if (outcome == SessionOutcome.OVERAGE) {
            val expiryAt = session.expiryAt ?: now
            ((now - expiryAt) / 1000L).toInt().coerceAtLeast(0)
        } else {
            null
        }
        finishedSessions.add(FinishedSession(session.pkg, now, outcome, overageS, session.extensions, roastShown))
        Log.i(TAG, "pkg=${session.pkg} finalized outcome=$outcome overageS=$overageS extensions=${session.extensions}")

        session.state = SessionState.IDLE
        session.grantedMin = null
        session.expiryAt = null
        session.intentText = null
        session.extensions = 0
        session.endingSince = null
    }
}
