package com.arkarizdev.bonked.core.gif

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T-208/T-209: the single orchestration entry point WatcherService calls
 * from precomputeRoast(). Composes [MemeGifProvider] (search + download)
 * with [GifCache] (disk write) under one combined budget — tech plan
 * §7c's "budget ~3s" covers the whole operation, not per-call — though
 * live testing against the real API showed 3000ms is too tight in
 * practice: search and download hit two different hosts (api.giphy.com,
 * media*.giphy.com), each needing its own DNS+TLS handshake, and a cold
 * cross-host handshake pair routinely eats 2s+ on its own before a single
 * byte of the actual GIF downloads — confirmed live via a real key, where
 * search consistently succeeded but the shared budget expired mid-download.
 * Bumped to 6s, still well inside the "grant flow has slack" reasoning
 * §7c itself gives for why this doesn't need to race the 1.5s roast-latency
 * budget from §1.
 *
 * [provider] is null whenever no API key is configured — that's a fully
 * supported state (not a degraded one): [fetch] short-circuits to
 * [FetchResult.Unavailable] immediately, without attempting a network call
 * or waiting out the timeout, so "no key" is strictly faster than a real
 * timeout, not just equivalent to one.
 *
 * @param fetchBudgetMs overridable only so tests can prove the timeout
 *   path fires without a real multi-second wait; production always uses
 *   the default.
 */
class GifFetchGateway(
    private val provider: MemeGifProvider?,
    private val cache: GifCache,
    private val fetchBudgetMs: Long = DEFAULT_FETCH_BUDGET_MS,
) {
    companion object {
        private const val DEFAULT_FETCH_BUDGET_MS = 6_000L
    }

    sealed class FetchResult {
        data class Fetched(val localPath: String, val gifId: String, val onSentUrl: String?) : FetchResult()
        data object Unavailable : FetchResult()
    }

    suspend fun fetch(moodId: String, queries: List<String>, sessionId: Long): FetchResult {
        val provider = provider ?: return FetchResult.Unavailable
        if (queries.isEmpty()) return FetchResult.Unavailable

        return try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(fetchBudgetMs) {
                    val result = provider.search(queries) ?: return@withTimeoutOrNull FetchResult.Unavailable
                    val dest = cache.fileFor(sessionId, moodId)
                    val cached = cache.write(dest) { provider.downloadBytes(result.downloadUrl) }
                    if (cached) {
                        FetchResult.Fetched(localPath = dest.path, gifId = result.id, onSentUrl = result.onSentUrl)
                    } else {
                        FetchResult.Unavailable
                    }
                } ?: FetchResult.Unavailable
            }
        } catch (t: CancellationException) {
            // withTimeoutOrNull already resolved its own timeout internally
            // (that's what the `?: FetchResult.Unavailable` above is for) —
            // reaching here means the caller's own scope was cancelled from
            // outside (e.g. the service shutting down), which must keep
            // propagating, not be reinterpreted as "no GIF available."
            throw t
        } catch (_: Exception) {
            FetchResult.Unavailable
        }
    }

    suspend fun registerUsed(onSentUrl: String) {
        provider?.registerUsed(onSentUrl)
    }
}
