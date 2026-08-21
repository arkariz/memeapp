package com.arkarizdev.grudge.core.watcher

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local-calendar-day math for the streak engine (T-201) and the home
 * screen's "today's damage" usage bars. Pure — no Android Context — so
 * both are unit-testable without Robolectric. minSdk 29 has java.time
 * natively, no desugaring needed.
 */
object DayBoundary {
    const val ONE_DAY_MS = 86_400_000L

    fun isoDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    fun startOfDayMs(isoDate: String, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.parse(isoDate).atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDayMs(isoDate: String, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.parse(isoDate).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
