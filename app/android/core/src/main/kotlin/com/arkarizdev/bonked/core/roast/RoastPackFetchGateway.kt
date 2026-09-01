package com.arkarizdev.bonked.core.roast

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * T-207: fetches the static manifest, and the pack zip when it's newer
 * than what's applied, then hands the bytes to [RoastPackStore] to verify
 * and swap. Mirrors GifFetchGateway's coroutine-bridged-OkHttp shape
 * (§7c) — a timeout at the call site must actually cancel the socket, not
 * just stop waiting on it.
 *
 * [manifestUrl] blank is a fully supported state (no manifest configured
 * yet), same as GIPHY_API_KEY blank meaning no gif provider — [syncIfNeeded]
 * short-circuits without any network call.
 */
class RoastPackFetchGateway(
    private val manifestUrl: String,
    private val client: OkHttpClient,
    private val store: RoastPackStore,
    private val fetchBudgetMs: Long = DEFAULT_FETCH_BUDGET_MS,
) {
    companion object {
        private const val TAG = "BonkedRoastPackSync"

        // A whole pack zip (up to ~15MB per §7's validator cap), not a
        // single GIF — much more slack than §7c's 6s GIF budget, and this
        // runs on a daily background job, never racing a user-facing
        // latency budget.
        private const val DEFAULT_FETCH_BUDGET_MS = 60_000L
    }

    suspend fun syncIfNeeded() {
        if (manifestUrl.isBlank()) return
        try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(fetchBudgetMs) { syncOnce() }
            }
        } catch (t: CancellationException) {
            throw t // propagate real cancellation (e.g. WorkManager stopping the job), never swallow it
        } catch (t: Exception) {
            Log.w(TAG, "sync failed", t)
        }
    }

    private suspend fun syncOnce() {
        val manifestJson = fetchText(manifestUrl) ?: return
        val manifest = RoastPackManifest.parse(manifestJson)
        if (manifest == null) {
            Log.w(TAG, "manifest failed to parse")
            return
        }
        val current = store.currentVersion() ?: 0
        if (manifest.latest <= current) return

        val zipBytes = fetchBytes(manifest.url) ?: return
        val applied = store.applyDownloadedPack(manifest.latest, zipBytes, manifest.sha256)
        Log.i(TAG, "pack sync version=${manifest.latest} applied=$applied")
    }

    private suspend fun fetchText(url: String): String? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (cont.isActive) cont.resume(if (it.isSuccessful) it.body?.string() else null)
                }
            }
        })
    }

    private suspend fun fetchBytes(url: String): ByteArray? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (cont.isActive) cont.resume(if (it.isSuccessful) it.body?.bytes() else null)
                }
            }
        })
    }
}
