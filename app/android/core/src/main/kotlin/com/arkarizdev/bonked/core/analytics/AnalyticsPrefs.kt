package com.arkarizdev.bonked.core.analytics

import android.content.Context
import java.util.UUID

/**
 * T-203: a single random, non-reversible per-install id sent to PostHog as
 * `distinct_id` so events from the same device can be grouped into a
 * cohort without identifying the person — not tied to any account, email,
 * or advertising id (none of those exist anywhere in this app). Deliberately
 * SharedPreferences, not Room — same one-value-no-query reasoning as
 * OnboardingPrefs.
 */
object AnalyticsPrefs {
    private const val PREFS_NAME = "bonked_analytics"
    private const val KEY_DISTINCT_ID = "distinct_id"

    fun distinctId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DISTINCT_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DISTINCT_ID, generated).apply()
        return generated
    }
}
