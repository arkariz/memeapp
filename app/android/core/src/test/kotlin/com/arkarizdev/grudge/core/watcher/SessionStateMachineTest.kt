package com.arkarizdev.grudge.core.watcher

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
    fun `markDone moves ROASTING to ENDING then tick clears it to IDLE`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L) // -> ROASTING

        val ok = sm.markDone(pkg, now = t0 + 5 * 60_000L)
        assertTrue(ok)
        assertEquals(SessionState.ENDING, sm.snapshot(pkg)?.state)

        sm.tick(t0 + 5 * 60_000L)
        val session = sm.snapshot(pkg)!!
        assertEquals(SessionState.IDLE, session.state)
        assertNull(session.grantedMin)
        assertNull(session.expiryAt)
        assertEquals(0, session.extensions)
    }

    @Test
    fun `app leaving while ROASTING transitions to ENDING`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)
        sm.tick(t0 + 5 * 60_000L) // -> ROASTING

        sm.onAppLeft(pkg, now = t0 + 5 * 60_000L + 100)
        assertEquals(SessionState.ENDING, sm.snapshot(pkg)?.state)
    }

    @Test
    fun `app leaving while RUNNING is untouched (wall-clock expiry, not paused)`() {
        val sm = SessionStateMachine()
        sm.onAppForegrounded(pkg, t0)
        sm.grant(pkg, minutes = 5, intentText = null, now = t0)

        sm.onAppLeft(pkg, now = t0 + 1_000) // left well before expiry
        assertEquals(SessionState.RUNNING, sm.snapshot(pkg)?.state)
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
