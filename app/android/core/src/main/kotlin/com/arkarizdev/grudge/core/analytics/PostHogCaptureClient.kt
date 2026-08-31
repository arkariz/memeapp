package com.arkarizdev.grudge.core.analytics

import android.util.Log
import com.arkarizdev.grudge.core.data.AnalyticsEvtEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Extracted so AnalyticsGateway's tests can fake the send step without a real HTTP call — mirrors MemeGifProvider's role for GifFetchGateway. */
interface AnalyticsSendClient {
    suspend fun sendBatch(distinctId: String, events: List<AnalyticsEvtEntity>): Boolean
}

/**
 * T-203: PostHog's raw `batch` REST endpoint — one JSON POST per flush,
 * deliberately not the PostHog SDK (which auto-captures device/session
 * data beyond what this app's no-PII stance allows; see AnalyticsCore's
 * allowlist for what actually gets sent). Host defaults to PostHog's US
 * Cloud ingestion endpoint — no PostHog account exists yet as of this
 * writing, so this is the documented default, not a confirmed region;
 * change CAPTURE_URL if the eventual account is EU-hosted or self-hosted.
 */
class PostHogCaptureClient(private val apiKey: String, private val client: OkHttpClient) : AnalyticsSendClient {
    companion object {
        private const val TAG = "BonkedAnalytics"
        private const val CAPTURE_URL = "https://us.i.posthog.com/batch/"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Builds PostHog's `batch` request body. Pulled out as a pure
         * function so this JSON-shape logic is unit-testable without a
         * real HTTP call, same reasoning as GiphyMemeGifProvider's
         * parseSearchResults.
         */
        internal fun buildBatchBody(apiKey: String, distinctId: String, events: List<AnalyticsEvtEntity>): String {
            val batch = JSONArray()
            for (evt in events) {
                val entry = JSONObject()
                entry.put("event", evt.name)
                entry.put("distinct_id", distinctId)
                entry.put("timestamp", Instant.ofEpochMilli(evt.createdAt).toString())
                entry.put("properties", JSONObject(evt.propsJson))
                batch.put(entry)
            }
            val root = JSONObject()
            root.put("api_key", apiKey)
            root.put("batch", batch)
            return root.toString()
        }
    }

    /** Returns true only on a real 2xx from PostHog — caller marks rows sent only then. */
    override suspend fun sendBatch(distinctId: String, events: List<AnalyticsEvtEntity>): Boolean {
        if (events.isEmpty()) return true
        val body = buildBatchBody(apiKey, distinctId, events).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(CAPTURE_URL).post(body).build()
        return try {
            execute(request) != null
        } catch (t: CancellationException) {
            throw t // never swallow — project-wide rule for network suspend functions
        } catch (t: Exception) {
            Log.w(TAG, "capture batch failed", t)
            false
        }
    }

    /** Bridges OkHttp's async call through a coroutine — same shape as GiphyMemeGifProvider.execute(). */
    private suspend fun execute(request: Request): String? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (cont.isActive) cont.resume(null)
                        return
                    }
                    if (cont.isActive) cont.resume(it.body?.string())
                }
            }
        })
    }
}
