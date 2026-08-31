package com.arkarizdev.grudge.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The no-PII audit's regression guard (PRD P0-7): asserts the allowlist
 * covers exactly the 9 events named in tech plan §6, no more, no less.
 * logEvent/logEventFromBridge's actual enforcement (require()/filterKeys)
 * needs a real Context (GrudgeDatabase.get()) to exercise end to end — that
 * path is live-verified on-device, same as every other Context-dependent
 * function in this codebase (RoastEngine.precompute, GifFetchGateway.fetch).
 */
class AnalyticsCoreTest {
    @Test
    fun `allowlist covers exactly the 9 tech-plan events`() {
        val expected = setOf(
            "onboarding_step",
            "grant",
            "extension",
            "roast_shown",
            "roast_outcome",
            "session_end",
            "card_generated",
            "card_shared",
            "watch_down",
        )

        assertEquals(expected, AnalyticsCore.allowlistedFieldsByEvent.keys)
    }

    @Test
    fun `no allowlist entry contains free-text-shaped field names`() {
        val bannedFieldNames = setOf("intentText", "intent_text", "line1", "line2", "caption", "text")
        for ((name, fields) in AnalyticsCore.allowlistedFieldsByEvent) {
            assertEquals(
                "event=$name must not allowlist any of $bannedFieldNames",
                emptySet<String>(),
                fields.intersect(bannedFieldNames),
            )
        }
    }
}
