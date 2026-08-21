package com.arkarizdev.grudge.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * tech plan §4's `roast_payload` table, extended per §7c with gifSource/
 * gifLocalPath. Schema only for now — no writer until T-105 (RoastEngine
 * precompute-at-grant) exists; nothing reads or writes this table yet.
 */
@Entity(tableName = "roast_payload")
data class RoastPayloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tier: Int,
    val line1: String,
    val line2: String,
    val assetRef: String,
    val createdAt: Long,
    val gifSource: String, // TENOR | BUNDLED — see tech plan §7c
    val gifLocalPath: String?,
)

@Dao
interface RoastPayloadDao {
    @Insert
    suspend fun insert(payload: RoastPayloadEntity): Long

    @Query("SELECT * FROM roast_payload WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun latestFor(sessionId: Long): RoastPayloadEntity?

    /** Lifetime roast count — feeds the overlay's "ROAST #N" eyebrow. */
    @Query("SELECT COUNT(*) FROM roast_payload")
    suspend fun countAll(): Int
}
