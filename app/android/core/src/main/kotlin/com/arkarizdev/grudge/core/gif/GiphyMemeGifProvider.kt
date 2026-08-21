package com.arkarizdev.grudge.core.gif

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
        private const val TAG = "GrudgeGifFetch"
        private const val SEARCH_URL = "https://api.giphy.com/v1/gifs/search"
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
            .addQueryParameter("limit", "1")
            .addQueryParameter("rating", "g")
            .addQueryParameter("lang", "en")
            .build()
        return try {
            val body = execute(Request.Builder().url(url).build()) ?: return null
            val data = JSONObject(body).optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val gif = data.getJSONObject(0)
            val images = gif.optJSONObject("images") ?: return null
            val rendition = images.optJSONObject("fixed_height") ?: images.optJSONObject("downsized")
            val downloadUrl = rendition?.optString("url")?.takeIf { it.isNotBlank() } ?: return null
            val onSentUrl = gif.optJSONObject("analytics")?.optJSONObject("onsent")?.optString("url")?.takeIf { it.isNotBlank() }
            MemeGifResult(id = gif.getString("id"), downloadUrl = downloadUrl, onSentUrl = onSentUrl)
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
