package com.arkarizdev.grudge.core.analytics

import com.arkarizdev.grudge.core.data.AnalyticsEvtDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T-203: the single orchestration entry point AnalyticsFlushWorker calls.
 * Mirrors GifFetchGateway's shape — [client] is null whenever no PostHog
 * API key is configured, which is a fully supported state (not a degraded
 * one): [flush] short-circuits to success immediately without a network
 * attempt, so queued events just accumulate locally until a key exists.
 *
 * @param flushBudgetMs overridable only so tests can prove the timeout
 *   path fires without a real multi-second wait; production always uses
 *   the default.
 */
class AnalyticsGateway(
    private val client: AnalyticsSendClient?,
    private val dao: AnalyticsEvtDao,
    private val distinctId: String,
    private val flushBudgetMs: Long = DEFAULT_FLUSH_BUDGET_MS,
) {
    companion object {
        private const val DEFAULT_FLUSH_BUDGET_MS = 10_000L
    }

    /** Never throws (except CancellationException). Returns whether the flush succeeded — callers don't need to branch on it, but tests do. */
    suspend fun flush(): Boolean {
        val client = client ?: return true
        val pending = dao.unsent()
        if (pending.isEmpty()) return true

        return try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(flushBudgetMs) {
                    val ok = client.sendBatch(distinctId, pending)
                    if (ok) dao.markSent(pending.map { it.id }, System.currentTimeMillis())
                    ok
                } ?: false
            }
        } catch (t: CancellationException) {
            throw t // outer scope cancellation must keep propagating
        } catch (_: Exception) {
            false
        }
    }
}
