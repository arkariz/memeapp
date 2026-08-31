package com.arkarizdev.bonked.core.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the state machine described in SessionState.kt. */
class SessionStateMachineTest {
    private val pkg = "com.example.watched"
    private val t0 = 1_000_000L

    @Test
    fun `foregrounding a fresh package moves it to INTENT_PENDING`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        assertEquals(SessionState.INTENT_PENDING, sm.snapshot(pkg)?.state)
    }

    @Test
    fun `foregrounding an already-active package is a no-op`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.onAppForegrounded(pkg, t0 + 1_000) // re-resumed mid-session
        assertEquals(SessionState.RUNNING, sm.snapshot(pkg)?.state)
    }

    @Test
    fun `grant moves INTENT_PENDING to RUNNING with a wall-clock expiry`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        val ok = sm.grant(pkg, minutes = 5, intentText = "just checking one thing", now = t0)
        val session = sm.snapshot(pkg)!!
        assertTrue(ok)
        assertEquals(SessionState.RUNNING, session.state)
        assertEquals(5, session.grantedMin)
        assertEquals("just checking one thing", session.intentText)
        assertEquals(t0 + 5 * 60_000L, session.expiryAt)
    }

    @Test
    fun `grant is rejected outside INTENT_PENDING`() {
        val sm = SessionStateMachine()
        // No onAppForegrounded call — session doesn't exist / is IDLE.
        val ok = sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        assertFalse(ok)
        assertNull(sm.snapshot(pkg))
    }

    @Test
    fun `tick moves RUNNING to ROASTING once expiry passes`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)

        sm.tick(t0 + 4 * 60_000L) // before expiry
        assertEquals(SessionState.RUNNING, sm.snapshot(pkg)?.state)

        sm.tick(t0 + 5 * 60_000L) // exactly at expiry
        assertEquals(SessionState.ROASTING, sm.snapshot(pkg)?.state)
    }

    @Test
    fun `extend moves ROASTING back to RUNNING and increments extensions`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L) // -> ROASTING

        val ok = sm.extend(pkg, additionalMinutes = 5, now = t0 + 5 * 60_000L)
        val session = sm.snapshot(pkg)!!
        assertTrue(ok)
        assertEquals(SessionState.RUNNING, session.state)
        assertEquals(1, session.extensions)
        assertEquals(t0 + 10 * 60_000L, session.expiryAt)
    }

    @Test
    fun `extend is rejected outside ROASTING`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0) // still RUNNING
        val ok = sm.extend(pkg, additionalMinutes = 5, now = t0)
        assertFalse(ok)
        assertEquals(0, sm.snapshot(pkg)?.extensions)
    }

    @Test
    fun `markDone finalizes ROASTING to IDLE immediately, no grace`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L) // -> ROASTING

        val ok = sm.markDone(pkg, now = t0 + 5 * 60_000L)
        assertTrue(ok)

        val session = sm.snapshot(pkg)!!
        assertEquals(SessionState.IDLE, session.state)
        assertNull(session.grantedMin)
        assertNull(session.expiryAt)
        assertEquals(0, session.extensions)
    }

    @Test
    fun `markDone with no extensions finalizes BEATEN, not OVERAGE`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L) // -> ROASTING at exactly expiry

        sm.markDone(pkg, now = t0 + 5 * 60_000L + 12_000L) // 12s after expiry
        val finished = sm.drainFinishedSessions()
        assertEquals(1, finished.size)
        assertEquals(SessionOutcome.BEATEN, finished[0].outcome)
        assertNull(finished[0].overageS)
    }

    @Test
    fun `app leaving ROASTING without a done tap still finalizes OVERAGE`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L) // -> ROASTING at exactly expiry

        sm.onAppLeft(pkg, now = t0 + 5 * 60_000L + 12_000L) // walked away instead of tapping done
        val finished = sm.drainFinishedSessions()
        assertEquals(1, finished.size)
        assertEquals(SessionOutcome.OVERAGE, finished[0].outcome)
        assertEquals(12, finished[0].overageS)
    }

    @Test
    fun `markDone after an extension finalizes EXTENDED, not OVERAGE`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L) // -> ROASTING
        sm.extend(pkg, additionalMinutes = 5, now = t0 + 5 * 60_000L) // -> RUNNING, extensions=1
        sm.tick(t0 + 10 * 60_000L) // -> ROASTING again

        sm.markDone(pkg, now = t0 + 10 * 60_000L)
        val finished = sm.drainFinishedSessions()
        assertEquals(1, finished.size)
        assertEquals(SessionOutcome.EXTENDED, finished[0].outcome)
        assertEquals(1, finished[0].extensions)
        assertNull(finished[0].overageS)
    }

    @Test
    fun `app leaving while ROASTING finalizes immediately, no grace`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L) // -> ROASTING

        sm.onAppLeft(pkg, now = t0 + 5 * 60_000L + 100)
        assertEquals(SessionState.IDLE, sm.snapshot(pkg)?.state)

        val finished = sm.drainFinishedSessions()
        assertEquals(1, finished.size)
        assertEquals(SessionOutcome.OVERAGE, finished[0].outcome)
    }

    @Test
    fun `app leaving while RUNNING starts the grace window, not an immediate outcome`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)

        sm.onAppLeft(pkg, now = t0 + 1_000) // left well before expiry
        assertEquals(SessionState.ENDING, sm.snapshot(pkg)?.state)
        assertTrue(sm.drainFinishedSessions().isEmpty()) // not finalized yet
    }

    @Test
    fun `returning within the grace window resumes RUNNING unchanged`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        val originalExpiry = sm.snapshot(pkg)!!.expiryAt

        sm.onAppLeft(pkg, now = t0 + 1_000)
        sm.onAppForegrounded(pkg, now = t0 + 10_000) // back within 60s

        val session = sm.snapshot(pkg)!!
        assertEquals(SessionState.RUNNING, session.state)
        assertEquals(originalExpiry, session.expiryAt) // clock never paused, never reset
        assertTrue(sm.drainFinishedSessions().isEmpty())
    }

    @Test
    fun `no return within the grace window finalizes BEATEN`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)

        sm.onAppLeft(pkg, now = t0 + 1_000)
        sm.tick(t0 + 1_000 + 59_000L) // grace not yet elapsed
        assertEquals(SessionState.ENDING, sm.snapshot(pkg)?.state)

        sm.tick(t0 + 1_000 + 60_000L) // grace elapsed, no return
        val session = sm.snapshot(pkg)!!
        assertEquals(SessionState.IDLE, session.state)

        val finished = sm.drainFinishedSessions()
        assertEquals(1, finished.size)
        assertEquals(SessionOutcome.BEATEN, finished[0].outcome)
        assertNull(finished[0].overageS)
    }

    @Test
    fun `grace-window BEATEN is overridden to EXTENDED if the session was ever extended`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L) // -> ROASTING
        sm.extend(pkg, additionalMinutes = 5, now = t0 + 5 * 60_000L) // -> RUNNING, extensions=1

        sm.onAppLeft(pkg, now = t0 + 6 * 60_000L) // leaves early against the NEW deadline
        sm.tick(t0 + 6 * 60_000L + 60_000L) // grace elapsed

        val finished = sm.drainFinishedSessions()
        assertEquals(1, finished.size)
        assertEquals(SessionOutcome.EXTENDED, finished[0].outcome)
    }

    @Test
    fun `drainFinishedSessions clears after reading`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L)
        sm.markDone(pkg, now = t0 + 5 * 60_000L)

        assertEquals(1, sm.drainFinishedSessions().size)
        assertTrue(sm.drainFinishedSessions().isEmpty())
    }

    @Test
    fun `abandoning the intent overlay resets to IDLE, not stuck`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0) // -> INTENT_PENDING, no grant yet

        sm.onAppLeft(pkg, now = t0 + 500) // user backed out without granting
        assertEquals(SessionState.IDLE, sm.snapshot(pkg)?.state)

        // Un-stuck: opening the app again shows the overlay, doesn't no-op.
        sm.onAppForegrounded(pkg, now = t0 + 10_000)
        assertEquals(SessionState.INTENT_PENDING, sm.snapshot(pkg)?.state)
    }

    @Test
    fun `activeSessionCount excludes IDLE sessions`() {
        val sm = SessionStateMachine()
        val other = "com.example.other"
        sm.onAppForegrounded(pkg, t0)
        sm.onAppForegrounded(other, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        // `other` stays INTENT_PENDING (never granted).
        assertEquals(2, sm.activeSessionCount())
    }

    @Test
    fun `allActiveSessions excludes IDLE and returns copies`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = "test", now = t0)

        val active = sm.allActiveSessions()
        assertEquals(1, active.size)
        assertEquals(pkg, active[0].pkg)

        // Mutating the returned copy must not affect internal state.
        active[0].extensions = 99
        assertEquals(0, sm.snapshot(pkg)?.extensions)
    }

    @Test
    fun `restore seeds a session as RUNNING from a persisted grant`() {
        val sm = SessionStateMachine()
        val persisted = Session(
            pkg = pkg,
            state = SessionState.IDLE, // irrelevant — restore() forces RUNNING
            intentText = "just checking one thing",
            grantedMin = 10,
            expiryAt = t0 + 10 * 60_000L,
            extensions = 1,
        )

        sm.restore(listOf(persisted))

        val session = sm.snapshot(pkg)!!
        assertEquals(SessionState.RUNNING, session.state)
        assertEquals(10, session.grantedMin)
        assertEquals("just checking one thing", session.intentText)
        assertEquals(1, session.extensions)
    }

    @Test
    fun `restored session naturally becomes ROASTING on the first tick if expiry already passed`() {
        val sm = SessionStateMachine()
        val expiredWhileDead = Session(
            pkg = pkg,
            grantedMin = 5,
            expiryAt = t0 - 1_000, // expiry was in the past when the service restarted
        )
        sm.restore(listOf(expiredWhileDead))

        sm.tick(t0) // the first poll after restart
        assertEquals(SessionState.ROASTING, sm.snapshot(pkg)?.state)
    }
}
