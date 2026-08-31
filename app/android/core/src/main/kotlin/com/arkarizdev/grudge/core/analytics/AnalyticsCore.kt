package com.arkarizdev.grudge.core.analytics

import android.content.Context
import android.util.Log
import com.arkarizdev.grudge.core.BuildConfig
import com.arkarizdev.grudge.core.data.AnalyticsEvtEntity
import com.arkarizdev.grudge.core.data.GrudgeDatabase
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * T-203: the single write path for analytics_evt. Every event in this app
 * — Kotlin-originated (grant, extension, roast_shown, session_end,
 * roast_outcome, watch_down) or Dart-originated via the logAnalyticsEvent
 * Pigeon method (onboarding_step, card_generated, card_shared) — funnels
 * through [logEvent]. This is the no-PII audit's actual enforcement
 * mechanism (PRD P0-7): [allowlistedFieldsByEvent] is the complete,
 * reviewable list of what may ever leave the device per event, checked in
 * code on every write, not just at review time.
 */
object AnalyticsCore {
    private const val TAG = "BonkedAnalytics"

    /**
     * The complete field allowlist per event name — deliberately exhaustive
     * and hardcoded here, not derived from whatever properties a call site
     * happens to pass. intentText, roast copy (line1/line2), card captions,
     * and any other free-text/user-generated content must never appear in
     * any of these sets. `pkg` (grant/extension) is a reviewed exception:
     * it's a watched-app package the user themselves picked in the T-109
     * app picker, already covered by the Play Console usage-access
     * declaration — not third-party PII.
     */
    internal val allowlistedFieldsByEvent: Map<String, Set<String>> = mapOf(
        "onboarding_step" to setOf("step", "ok"),
        "grant" to setOf("pkg", "min", "has_intent"),
        "extension" to setOf("pkg", "n"),
        "roast_shown" to setOf("tier", "latency_ms"),
        "roast_outcome" to setOf("outcome", "secs_to_action"),
        "session_end" to setOf("outcome", "overage_s"),
        "card_generated" to setOf("type"),
        "card_shared" to setOf("type"),
        "watch_down" to setOf("reason", "oem", "uptime_s"),
    )

    /**
     * Direct Kotlin call sites: throws on any unlisted key — these are all
     * internal, always-correct-by-construction, so a mismatch here is a
     * real bug worth crashing a debug build over, not something to
     * silently paper over.
     */
    suspend fun logEvent(context: Context, name: String, props: Map<String, Any?>) {
        val allowed = allowlistedFieldsByEvent[name]
            ?: throw IllegalArgumentException("unknown analytics event name: $name")
        require(props.keys == allowed) { "event=$name props keys ${props.keys} != allowlist $allowed" }
        insert(context, name, props)
    }

    /**
     * Dart-originated call sites (via the Pigeon logAnalyticsEvent method):
     * an untyped bridge can carry an inconsistency that isn't a compile-time
     * bug on either side, so this path drops unlisted keys and warns rather
     * than crashing a release build over it.
     */
    suspend fun logEventFromBridge(context: Context, name: String, props: Map<String, Any?>) {
        val allowed = allowlistedFieldsByEvent[name]
        if (allowed == null) {
            Log.w(TAG, "logEventFromBridge: unknown event name=$name, dropped")
            return
        }
        val filtered = props.filterKeys { it in allowed }
        if (filtered.keys != props.keys) {
            Log.w(TAG, "logEventFromBridge: event=$name dropped unlisted keys ${props.keys - allowed}")
        }
        insert(context, name, filtered)
    }

    private suspend fun insert(context: Context, name: String, props: Map<String, Any?>) {
        val db = GrudgeDatabase.get(context)
        db.analyticsEvtDao().insert(
            AnalyticsEvtEntity(
                name = name,
                propsJson = JSONObject(props).toString(),
                createdAt = System.currentTimeMillis(),
            )
        )
        AnalyticsFlushWorker.schedule(context)
    }

    /** Builds a gateway from BuildConfig.POSTHOG_API_KEY — same conditional-construction pattern as WatcherService's gifProvider. */
    fun buildGateway(context: Context): AnalyticsGateway {
        val apiKey = BuildConfig.POSTHOG_API_KEY
        val client = if (apiKey.isNotBlank()) PostHogCaptureClient(apiKey, OkHttpClient()) else null
        val db = GrudgeDatabase.get(context)
        return AnalyticsGateway(client, db.analyticsEvtDao(), AnalyticsPrefs.distinctId(context))
    }
}
