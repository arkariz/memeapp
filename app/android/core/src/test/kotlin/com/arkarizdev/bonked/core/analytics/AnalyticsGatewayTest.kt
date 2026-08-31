package com.arkarizdev.bonked.core.analytics

import com.arkarizdev.bonked.core.data.AnalyticsEvtDao
import com.arkarizdev.bonked.core.data.AnalyticsEvtEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory fake — no network, so these tests run fast and deterministically. */
private class FakeAnalyticsSendClient(
    private val sendResult: Boolean = true,
    private val sendDelayMs: Long = 0,
    private val sendThrows: Boolean = false,
) : AnalyticsSendClient {
    var sendCalls = 0
        private set
    var lastEvents: List<AnalyticsEvtEntity>? = null
        private set

    override suspend fun sendBatch(distinctId: String, events: List<AnalyticsEvtEntity>): Boolean {
        sendCalls++
        lastEvents = events
        if (sendDelayMs > 0) delay(sendDelayMs)
        if (sendThrows) throw RuntimeException("network down")
        return sendResult
    }
}

/** In-memory fake — this codebase has no DAO-integration test infra (no in-memory Room precedent), so a hand-rolled fake matches the existing style. */
private class FakeAnalyticsEvtDao(seed: List<AnalyticsEvtEntity> = emptyList()) : AnalyticsEvtDao {
    private val rows = seed.toMutableList()

    override suspend fun insert(evt: AnalyticsEvtEntity): Long {
        val withId = evt.copy(id = (rows.maxOfOrNull { it.id } ?: 0) + 1)
        rows.add(withId)
        return withId.id
    }

    override suspend fun unsent(): List<AnalyticsEvtEntity> = rows.filter { it.sentAt == null }

    override suspend fun markSent(ids: List<Long>, sentAt: Long) {
        for (i in rows.indices) {
            if (rows[i].id in ids) rows[i] = rows[i].copy(sentAt = sentAt)
        }
    }

    fun snapshot(): List<AnalyticsEvtEntity> = rows.toList()
}

class AnalyticsGatewayTest {
    private fun event(id: Long, name: String = "grant") =
        AnalyticsEvtEntity(id = id, name = name, propsJson = "{}", createdAt = 1_000L)

    @Test
    fun `null client returns success without calling the fake`() = runTest {
        val fake = FakeAnalyticsSendClient()
        val dao = FakeAnalyticsEvtDao(listOf(event(1)))
        val gateway = AnalyticsGateway(client = null, dao = dao, distinctId = "device-1")

        val ok = gateway.flush()

        assertTrue(ok)
        assertEquals(0, fake.sendCalls)
        assertNull(dao.snapshot().first().sentAt) // untouched, not marked sent
    }

    @Test
    fun `empty queue returns success without calling send`() = runTest {
        val fake = FakeAnalyticsSendClient()
        val gateway = AnalyticsGateway(fake, FakeAnalyticsEvtDao(), distinctId = "device-1")

        val ok = gateway.flush()

        assertTrue(ok)
        assertEquals(0, fake.sendCalls)
    }

    @Test
    fun `successful send marks the sent rows`() = runTest {
        val fake = FakeAnalyticsSendClient(sendResult = true)
        val dao = FakeAnalyticsEvtDao(listOf(event(1), event(2)))
        val gateway = AnalyticsGateway(fake, dao, distinctId = "device-1")

        val ok = gateway.flush()

        assertTrue(ok)
        assertEquals(1, fake.sendCalls)
        assertEquals(2, fake.lastEvents?.size)
        assertTrue(dao.snapshot().all { it.sentAt != null })
    }

    @Test
    fun `failed send leaves rows unsent`() = runTest {
        val fake = FakeAnalyticsSendClient(sendResult = false)
        val dao = FakeAnalyticsEvtDao(listOf(event(1)))
        val gateway = AnalyticsGateway(fake, dao, distinctId = "device-1")

        val ok = gateway.flush()

        assertTrue(!ok)
        assertTrue(dao.snapshot().all { it.sentAt == null })
    }

    @Test
    fun `send exceeding the budget times out without marking sent`() = runTest {
        val fake = FakeAnalyticsSendClient(sendDelayMs = 500)
        val dao = FakeAnalyticsEvtDao(listOf(event(1)))
        val gateway = AnalyticsGateway(fake, dao, distinctId = "device-1", flushBudgetMs = 20)

        val ok = gateway.flush()

        assertTrue(!ok)
        assertTrue(dao.snapshot().all { it.sentAt == null })
    }

    @Test
    fun `send throwing yields failure, no crash`() = runTest {
        val fake = FakeAnalyticsSendClient(sendThrows = true)
        val dao = FakeAnalyticsEvtDao(listOf(event(1)))
        val gateway = AnalyticsGateway(fake, dao, distinctId = "device-1")

        val ok = gateway.flush()

        assertTrue(!ok)
    }
}
