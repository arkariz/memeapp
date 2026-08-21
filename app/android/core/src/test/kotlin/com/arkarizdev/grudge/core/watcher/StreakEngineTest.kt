package com.arkarizdev.grudge.core.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-logic tests for the day-boundary rollover described in StreakEngine.kt. */
class StreakEngineTest {
    @Test
    fun `first run ever only anchors to yesterday, no increment`() {
        val result = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = StreakSnapshot(current = 0, best = 0, lastCountedDay = ""),
            wasYesterdayClean = true,
        )
        assertEquals(StreakSnapshot(current = 0, best = 0, lastCountedDay = "2026-08-20"), result)
    }

    @Test
    fun `already resolved through yesterday returns null, no recomputation`() {
        val result = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = StreakSnapshot(current = 3, best = 5, lastCountedDay = "2026-08-20"),
            wasYesterdayClean = true,
        )
        assertNull(result)
    }

    @Test
    fun `already resolved through today returns null`() {
        val result = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = StreakSnapshot(current = 3, best = 5, lastCountedDay = "2026-08-21"),
            wasYesterdayClean = true,
        )
        assertNull(result)
    }

    @Test
    fun `clean day after exactly one elapsed day increments current and best`() {
        val result = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = StreakSnapshot(current = 5, best = 5, lastCountedDay = "2026-08-19"),
            wasYesterdayClean = true,
        )
        assertEquals(StreakSnapshot(current = 6, best = 6, lastCountedDay = "2026-08-20"), result)
    }

    @Test
    fun `dirty day after exactly one elapsed day resets current, best untouched`() {
        val result = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = StreakSnapshot(current = 5, best = 9, lastCountedDay = "2026-08-19"),
            wasYesterdayClean = false,
        )
        assertEquals(StreakSnapshot(current = 0, best = 9, lastCountedDay = "2026-08-20"), result)
    }

    @Test
    fun `best only updates when current surpasses it, never decreases`() {
        val result = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = StreakSnapshot(current = 2, best = 9, lastCountedDay = "2026-08-19"),
            wasYesterdayClean = true,
        )
        assertEquals(StreakSnapshot(current = 3, best = 9, lastCountedDay = "2026-08-20"), result)
    }

    @Test
    fun `a multi-day gap resets the streak regardless of wasYesterdayClean`() {
        // lastCountedDay is 3 days stale — the days in between were never
        // resolved (service dead, or a long absence), so the gap can't
        // have kept the streak alive even if wasYesterdayClean is true
        // (which itself only describes the day right after lastCountedDay,
        // not the whole gap).
        val result = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = StreakSnapshot(current = 5, best = 5, lastCountedDay = "2026-08-16"),
            wasYesterdayClean = true,
        )
        assertEquals(StreakSnapshot(current = 0, best = 5, lastCountedDay = "2026-08-17"), result)
    }

    @Test
    fun `gap resolution advances exactly one day so the next call resolves the following day`() {
        val gapResult = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = StreakSnapshot(current = 5, best = 5, lastCountedDay = "2026-08-16"),
            wasYesterdayClean = true,
        )!!
        assertEquals("2026-08-17", gapResult.lastCountedDay)

        // Next call still hasn't caught up to yesterday, so it resolves
        // one more day forward — still ignoring wasYesterdayClean since
        // 2026-08-18 != yesterdayIso.
        val secondResult = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = gapResult,
            wasYesterdayClean = true,
        )!!
        assertEquals(0, secondResult.current)
        assertEquals("2026-08-18", secondResult.lastCountedDay)
    }

    @Test
    fun `zero-usage day counts as clean when caller reports it as such`() {
        // StreakEngine itself doesn't know about usage — a zero-session day
        // is indistinguishable from any other clean day from its
        // perspective, which is the intended behavior (avoiding the apps
        // entirely is a win, not a non-event).
        val result = StreakEngine.evaluate(
            yesterdayIso = "2026-08-20",
            snapshot = StreakSnapshot(current = 0, best = 9, lastCountedDay = "2026-08-19"),
            wasYesterdayClean = true,
        )
        assertEquals(1, result?.current)
    }
}
