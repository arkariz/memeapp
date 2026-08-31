package com.arkarizdev.grudge.core.analytics

import com.arkarizdev.grudge.core.data.AnalyticsEvtEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PostHogCaptureClientTest {
    @Test
    fun `buildBatchBody wraps api_key and one entry per event`() {
        val events = listOf(
            AnalyticsEvtEntity(id = 1, name = "grant", propsJson = """{"pkg":"com.android.chrome","min":10,"has_intent":true}""", createdAt = 1_700_000_000_000L),
            AnalyticsEvtEntity(id = 2, name = "session_end", propsJson = """{"outcome":"BEATEN","overage_s":null}""", createdAt = 1_700_000_005_000L),
        )

        val body = JSONObject(PostHogCaptureClient.buildBatchBody("key-123", "device-1", events))

        assertEquals("key-123", body.getString("api_key"))
        val batch = body.getJSONArray("batch")
        assertEquals(2, batch.length())
    }

    @Test
    fun `each batch entry carries event, distinct_id, timestamp and properties`() {
        val events = listOf(
            AnalyticsEvtEntity(id = 1, name = "grant", propsJson = """{"pkg":"com.android.chrome","min":10,"has_intent":true}""", createdAt = 1_700_000_000_000L),
        )

        val body = JSONObject(PostHogCaptureClient.buildBatchBody("key-123", "device-1", events))
        val entry = body.getJSONArray("batch").getJSONObject(0)

        assertEquals("grant", entry.getString("event"))
        assertEquals("device-1", entry.getString("distinct_id"))
        assertEquals("2023-11-14T22:13:20Z", entry.getString("timestamp"))
        val props = entry.getJSONObject("properties")
        assertEquals("com.android.chrome", props.getString("pkg"))
        assertEquals(10, props.getInt("min"))
        assertEquals(true, props.getBoolean("has_intent"))
    }

    @Test
    fun `propsJson round-trips verbatim into properties`() {
        val events = listOf(
            AnalyticsEvtEntity(id = 1, name = "watch_down", propsJson = """{"reason":"service_dead","oem":"Google","uptime_s":900}""", createdAt = 0L),
        )

        val body = JSONObject(PostHogCaptureClient.buildBatchBody("key", "device-1", events))
        val props = body.getJSONArray("batch").getJSONObject(0).getJSONObject("properties")

        assertEquals("service_dead", props.getString("reason"))
        assertEquals("Google", props.getString("oem"))
        assertEquals(900, props.getInt("uptime_s"))
    }

    @Test
    fun `empty event list still produces a valid empty batch`() {
        val body = JSONObject(PostHogCaptureClient.buildBatchBody("key", "device-1", emptyList()))

        assertEquals(0, body.getJSONArray("batch").length())
    }
}
