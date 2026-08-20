package com.arkarizdev.grudge.core.roast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Only the pure logic (tier escalation, slot filling) is JVM-testable
 * without Robolectric — precompute() itself needs a real Context to load
 * roast_pack.json from assets, so that path is verified live on-device
 * instead (see T-105's entry in memory/tech-plan for the live-test record).
 */
class RoastEngineTest {
    @Test
    fun `tier escalates 1 to 2 to 3 and caps at 3`() {
        assertEquals(1, RoastEngine.tierFor(0))
        assertEquals(2, RoastEngine.tierFor(1))
        assertEquals(3, RoastEngine.tierFor(2))
        assertEquals(3, RoastEngine.tierFor(5))
    }

    @Test
    fun `fill substitutes every slot token`() {
        val text = "\"{intent}\" — {actual_min} min vs {granted_min} granted, ext #{extension_count}, tier {tier}"
        val slots = mapOf(
            "intent" to "just checking one thing",
            "actual_min" to "47",
            "granted_min" to "10",
            "overage_min" to "37",
            "extension_count" to "2",
            "tier" to "3",
        )
        val result = RoastEngine.fill(text, slots)
        assertEquals(
            "\"just checking one thing\" — 47 min vs 10 granted, ext #2, tier 3",
            result,
        )
    }

    @Test
    fun `fill leaves text unchanged when no tokens present`() {
        assertEquals("STILL SCROLLING.", RoastEngine.fill("STILL SCROLLING.", emptyMap()))
    }
}
