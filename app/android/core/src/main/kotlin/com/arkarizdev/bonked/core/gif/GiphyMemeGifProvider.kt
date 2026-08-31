package com.arkarizdev.bonked.core.gif

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * T-208: GIPHY as the concrete [MemeGifProvider] (swapped in for the
 * originally-scoped Tenor after Google fully shut Tenor's API down on
 * 2026-06-30 — see tech plan §7c). `rating=g` is GIPHY's equivalent of
 * Tenor's `contentfilter=high`, hardcoded rather than sourced from
 * roast_pack.json's Tenor-specific `contentfilter` field.
 */
class GiphyMemeGifProvider(private val apiKey: String, private val client: OkHttpClient) : MemeGifProvider {
    companion object {
        private const val TAG = "BonkedGifFetch"
        private const val SEARCH_URL = "https://api.giphy.com/v1/gifs/search"

        // A given mood's query text is deterministic (roast_pack.json's
        // fixed list), and GIPHY's search endpoint isn't randomized — with
        // limit=1 it returns the same top hit essentially every time a mood
        // recurs. Asking for more results and picking randomly among them
        // (still all rating=g) is what actually makes repeat moods look
        // different, on top of RoastEngine's own mood cooldown.
        private const val SEARCH_RESULT_LIMIT = "10"

        /**
         * Parses GIPHY's `data[]` array into usable results, skipping any
         * entry missing a renditon URL rather than failing the whole parse.
         * Pulled out as a pure function so this JSON-shape logic is
         * unit-testable without a real HTTP call.
         */
        internal fun parseSearchResults(body: String): List<MemeGifResult> {
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val results = mutableListOf<MemeGifResult>()
            for (i in 0 until data.length()) {
                val gif = data.optJSONObject(i) ?: continue
                val images = gif.optJSONObject("images") ?: continue
                val rendition = images.optJSONObject("fixed_height") ?: images.optJSONObject("downsized")
                val downloadUrl = rendition?.optString("url")?.takeIf { it.isNotBlank() } ?: continue
                val id = gif.optString("id")?.takeIf { it.isNotBlank() } ?: continue
                val onSentUrl = gif.optJSONObject("analytics")?.optJSONObject("onsent")?.optString("url")?.takeIf { it.isNotBlank() }
                results.add(MemeGifResult(id = id, downloadUrl = downloadUrl, onSentUrl = onSentUrl))
            }
            return results
        }
    }

    override suspend fun search(queries: List<String>): MemeGifResult? {
        for (query in queries) {
            val result = searchOne(query)
            if (result != null) return result
        }
        return null
    }

    private suspend fun searchOne(query: String): MemeGifResult? {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("q", query)
            .addQueryParameter("limit", SEARCH_RESULT_LIMIT)
            .addQueryParameter("rating", "g")
            .addQueryParameter("lang", "en")
            .build()
        return try {
            val body = execute(Request.Builder().url(url).build()) ?: return null
            parseSearchResults(body).randomOrNull()
        } catch (t: CancellationException) {
            throw t // never swallow — this is what lets GifFetchGateway's withTimeoutOrNull see the timeout
        } catch (t: Exception) {
            Log.w(TAG, "search failed for query=\"$query\"", t)
            null
        }
    }

    override suspend fun registerUsed(onSentUrl: String) {
        try {
            execute(Request.Builder().url(onSentUrl).build())
        } catch (t: CancellationException) {
            throw t
        } catch (t: Exception) {
            Log.w(TAG, "registerUsed pingback failed", t)
        }
    }

    override suspend fun downloadBytes(url: String): ByteArray? = try {
        executeForBytes(Request.Builder().url(url).build())
    } catch (t: CancellationException) {
        throw t
    } catch (t: Exception) {
        Log.w(TAG, "download failed url=$url", t)
        null
    }

    /** Bridges OkHttp's async call through a coroutine so a timeout at the call site actually cancels the socket. */
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

    private suspend fun executeForBytes(request: Request): ByteArray? = suspendCancellableCoroutine { cont ->
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
                    if (cont.isActive) cont.resume(it.body?.bytes())
                }
            }
        })
    }
}
