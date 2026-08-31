package com.arkarizdev.bonked.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * tech plan §4's `roast_payload` table, extended per §7c with gifSource/
 * gifLocalPath (Tenor was the originally-scoped provider; T-208/T-209
 * swapped it for GIPHY after Tenor's API was fully shut down by Google on
 * 2026-06-30 — see docs/memeapp-v1-tech-plan.md §7c for the full story).
 * No Room migration for the T-208/T-209 columns: version stays 1, same
 * precedent as gifSource/gifLocalPath's own preemptive addition — no
 * shipped install base yet.
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
    val gifSource: String, // GIPHY | BUNDLED — see tech plan §7c
    val gifLocalPath: String?,
    // T-208/T-209: only set when gifSource == GIPHY. Kept separate from
    // gifLocalPath rather than resolved at fetch time so the "used"
    // pingback (gifOnSentUrl) can fire at actual DISPLAY time — a
    // precomputed roast can be fetched at grant but never shown if the
    // session ends BEATEN before expiry, so firing at fetch time would
    // misreport GIFs as "used" that a user never saw.
    val gifId: String? = null,
    val gifOnSentUrl: String? = null,
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
