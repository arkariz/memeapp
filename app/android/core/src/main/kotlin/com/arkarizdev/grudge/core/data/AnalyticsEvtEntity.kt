package com.arkarizdev.grudge.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * tech plan §4's `analytics_evt` table — the local-first event queue from
 * §6. Written exclusively through AnalyticsCore.logEvent() (T-203), which
 * enforces the no-PII allowlist per event name — propsJson never carries
 * intent text or usage detail (P0-7 no-PII requirement).
 */
@Entity(tableName = "analytics_evt")
data class AnalyticsEvtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val propsJson: String,
    val createdAt: Long,
    val sentAt: Long? = null,
)

@Dao
interface AnalyticsEvtDao {
    @Insert
    suspend fun insert(evt: AnalyticsEvtEntity): Long

    @Query("SELECT * FROM analytics_evt WHERE sentAt IS NULL")
    suspend fun unsent(): List<AnalyticsEvtEntity>

    @Query("UPDATE analytics_evt SET sentAt = :sentAt WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>, sentAt: Long)
}
