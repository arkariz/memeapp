package com.arkarizdev.grudge.core.watcher

import com.arkarizdev.grudge.core.data.HeartbeatDao
import com.arkarizdev.grudge.core.data.HeartbeatEntity

/**
 * Durable heartbeat (last_tick_at / service_started_at) so watch-down
 * detection (T-110) can tell "the service died" apart from "the app was
 * just closed." Now backed by the real `heartbeat` table from T-103's
 * Room schema — this was explicitly an interim SharedPreferences store in
 * T-102, swappable without changing callers; this is that swap.
 */
class HeartbeatStore(private val dao: HeartbeatDao) {
    private var serviceStartedAt: Long? = null

    suspend fun recordTick(now: Long) {
        val startedAt = serviceStartedAt ?: now.also { serviceStartedAt = it }
        dao.upsert(HeartbeatEntity(lastTickAt = now, serviceStartedAt = startedAt))
    }

    suspend fun lastTickAt(): Long? = dao.get()?.lastTickAt

    suspend fun serviceStartedAt(): Long? = dao.get()?.serviceStartedAt
}
