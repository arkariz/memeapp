package com.arkarizdev.grudge.core.roast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun template(id: String, asset: String) = RoastTemplate(
    id = id,
    tier = 1,
    requiresIntentText = false,
    line1 = "line1",
    line2 = "line2",
    degradeLine1 = "d1",
    degradeLine2 = "d2",
    asset = asset,
)

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

    @Test
    fun `parseMoods reads the queries array for a known mood`() {
        val json = """
            {"moods": {"side_eye": {"queries": ["side eye", "suspicious look"], "contentfilter": "high"}}}
        """.trimIndent()
        assertEquals(listOf("side eye", "suspicious look"), RoastEngine.parseMoods(json)["side_eye"])
    }

    @Test
    fun `parseMoods ignores the _comment string entry and the contentfilter field`() {
        val json = """
            {"moods": {
                "_comment": "search query seeds for the live GIPHY fetch",
                "stonks": {"queries": ["stonks"], "contentfilter": "high"}
            }}
        """.trimIndent()
        val moods = RoastEngine.parseMoods(json)
        assertEquals(setOf("stonks"), moods.keys)
        assertEquals(listOf("stonks"), moods["stonks"])
    }

    @Test
    fun `parseMoods returns empty map when moods is missing entirely`() {
        assertEquals(emptyMap<String, List<String>>(), RoastEngine.parseMoods("""{"templates": []}"""))
    }

    @Test
    fun `parseMoods skips a mood entry with no queries array`() {
        val json = """{"moods": {"waiting": {"contentfilter": "high"}}}"""
        assertEquals(emptyMap<String, List<String>>(), RoastEngine.parseMoods(json))
    }

    @Test
    fun `eligiblePool excludes the last template id and recent moods when alternatives exist`() {
        val candidates = listOf(
            template("t1_01", "side_eye"),
            template("t1_02", "side_eye"),
            template("t1_03", "stonks"),
        )
        val pool = RoastEngine.eligiblePool(candidates, avoidId = "t1_01", avoidMoods = listOf("side_eye"))
        assertEquals(listOf("t1_03"), pool.map { it.id })
    }

    @Test
    fun `eligiblePool falls back to ignoring mood cooldown when it would empty the pool`() {
        // Every candidate is "side_eye" — honoring the mood cooldown would
        // leave nothing, so it must fall back to just avoiding the last id.
        val candidates = listOf(
            template("t1_01", "side_eye"),
            template("t1_02", "side_eye"),
        )
        val pool = RoastEngine.eligiblePool(candidates, avoidId = "t1_01", avoidMoods = listOf("side_eye"))
        assertEquals(listOf("t1_02"), pool.map { it.id })
    }

    @Test
    fun `eligiblePool falls back to the full candidate list for a single-candidate tier`() {
        val candidates = listOf(template("t3_01", "waiting"))
        val pool = RoastEngine.eligiblePool(candidates, avoidId = "t3_01", avoidMoods = listOf("waiting"))
        assertEquals(listOf("t3_01"), pool.map { it.id })
    }

    @Test
    fun `eligiblePool with no history returns every candidate`() {
        val candidates = listOf(template("t1_01", "side_eye"), template("t1_02", "stonks"))
        val pool = RoastEngine.eligiblePool(candidates, avoidId = null, avoidMoods = emptyList())
        assertEquals(2, pool.size)
    }

    @Test
    fun `eligiblePool with multiple recent moods excludes all of them`() {
        val candidates = listOf(
            template("t1_01", "side_eye"),
            template("t1_02", "stonks"),
            template("t1_03", "waiting"),
        )
        val pool = RoastEngine.eligiblePool(candidates, avoidId = null, avoidMoods = listOf("side_eye", "stonks"))
        assertEquals(listOf("t1_03"), pool.map { it.id })
        assertTrue(pool.all { it.asset !in listOf("side_eye", "stonks") })
    }
}
