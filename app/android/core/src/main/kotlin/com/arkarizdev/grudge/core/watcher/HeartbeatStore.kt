package com.arkarizdev.grudge.core.watcher

import android.content.Context

/**
 * Durable heartbeat (last_tick_at / service_started_at) so watch-down
 * detection (T-110) can tell "the service died" apart from "the app was
 * just closed." SharedPreferences is an interim store — the tech plan's
 * data model puts this in Room (§4), but T-103 hasn't landed yet and
 * heartbeat is this task's own deliverable, so it gets a minimal durable
 * store now rather than waiting on Room. Swapping this for a Room-backed
 * implementation later is a one-file change; callers only see this
 * interface's shape.
 */
class HeartbeatStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("grudge_heartbeat", Context.MODE_PRIVATE)

    fun recordTick(now: Long) {
        if (!prefs.contains(KEY_SERVICE_STARTED_AT)) {
            prefs.edit().putLong(KEY_SERVICE_STARTED_AT, now).apply()
        }
        prefs.edit().putLong(KEY_LAST_TICK_AT, now).apply()
    }

    fun recordServiceStopped() {
        prefs.edit().remove(KEY_SERVICE_STARTED_AT).apply()
    }

    fun lastTickAt(): Long? =
        prefs.getLong(KEY_LAST_TICK_AT, -1L).takeIf { it >= 0L }

    fun serviceStartedAt(): Long? =
        prefs.getLong(KEY_SERVICE_STARTED_AT, -1L).takeIf { it >= 0L }

    companion object {
        private const val KEY_LAST_TICK_AT = "last_tick_at"
        private const val KEY_SERVICE_STARTED_AT = "service_started_at"
    }
}
