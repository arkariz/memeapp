package com.arkarizdev.grudge.core.watcher

import java.time.LocalDate

/**
 * In-memory mirror of the `streak` table (tech plan §4). Not Room-backed
 * for the same reason [Session] isn't — this is the pure logic layer,
 * persistence is the caller's job.
 */
data class StreakSnapshot(val current: Int, val best: Int, val lastCountedDay: String)

/**
 * T-201 / PRD P0-5: "streak = consecutive days with all sessions beaten."
 * A day counts — streak continues — when no session ended that day with
 * an outcome other than BEATEN, including a day with zero watched-app
 * usage at all (avoiding the apps entirely reads as a win, not a
 * non-event).
 *
 * Pure and Context-free like SessionStateMachine — day-boundary rollover
 * is exactly the kind of off-by-one-prone logic that needs a JVM unit
 * test, not a live device. Resolves at most ONE day per call (advances
 * lastCountedDay forward by exactly one day); the caller runs this every
 * poll tick, so a multi-day gap (service was dead, or first launch after
 * a long absence) catches up within a couple of ticks, not instantly —
 * that's fine, nothing reads the streak on a hot path.
 */
object StreakEngine {
    /**
     * @param wasYesterdayClean whether the day immediately after
     *   [snapshot.lastCountedDay] — which, when exactly one day has
     *   elapsed, IS [yesterdayIso] — had no non-BEATEN session. Only
     *   consulted in that exact case; a bigger gap can't have kept the
     *   streak alive regardless, so the caller need not query for it.
     * @return the updated snapshot, or null if nothing is pending (already
     *   resolved through yesterday, or this is the very first call ever
     *   and there's nothing to fold in yet — the caller should still
     *   persist the anchor-only case below).
     */
    fun evaluate(yesterdayIso: String, snapshot: StreakSnapshot, wasYesterdayClean: Boolean): StreakSnapshot? {
        if (snapshot.lastCountedDay.isEmpty()) {
            // First run ever: nothing resolved yet, just anchor to
            // yesterday so tomorrow's call has a baseline to roll from.
            return snapshot.copy(lastCountedDay = yesterdayIso)
        }
        if (snapshot.lastCountedDay >= yesterdayIso) {
            // Already resolved through yesterday — nothing new until
            // today itself becomes "yesterday" tomorrow.
            return null
        }
        val nextDay = LocalDate.parse(snapshot.lastCountedDay).plusDays(1).toString()
        val countsAsClean = nextDay == yesterdayIso && wasYesterdayClean
        val newCurrent = if (countsAsClean) snapshot.current + 1 else 0
        return StreakSnapshot(current = newCurrent, best = maxOf(snapshot.best, newCurrent), lastCountedDay = nextDay)
    }
}
