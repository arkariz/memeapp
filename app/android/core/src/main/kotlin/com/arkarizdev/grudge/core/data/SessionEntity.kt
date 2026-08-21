package com.arkarizdev.grudge.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * tech plan §4's `session` table, matched field-for-field. T-103 only
 * populates the columns needed for "grant-reload-on-restart": pkg,
 * openedAt, intentText, grantedMin, expiryAt, extensions. endedAt/outcome/
 * overageS exist now (so this IS the documented schema) but are only ever
 * written by T-111's outcome-classification logic — until then a row with
 * endedAt == null just means "the currently active grant for this pkg."
 */
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val openedAt: Long,
    val intentText: String?,
    val grantedMin: Int,
    val expiryAt: Long,
    val extensions: Int,
    val endedAt: Long? = null,
    val outcome: String? = null, // T-111: BEATEN | OVERAGE | EXTENDED, see SessionOutcome
    val overageS: Int? = null,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM session WHERE pkg = :pkg AND endedAt IS NULL LIMIT 1")
    suspend fun findActive(pkg: String): SessionEntity?

    @Query("SELECT * FROM session WHERE endedAt IS NULL")
    suspend fun findAllActive(): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM session WHERE endedAt IS NULL")
    suspend fun activeCount(): Int

    /**
     * T-201 home screen: every session that overlaps today's window at
     * all, including the still-active one (endedAt IS NULL) — the caller
     * clamps each row to the window and to "now" itself, since a session
     * opened before midnight or still running shouldn't count minutes
     * outside today.
     */
    @Query("SELECT * FROM session WHERE pkg = :pkg AND openedAt < :end AND (endedAt IS NULL OR endedAt >= :start)")
    suspend fun sessionsOverlapping(pkg: String, start: Long, end: Long): List<SessionEntity>

    /**
     * T-201 streak engine: did any session ending in [start, end) finalize
     * as anything other than BEATEN? Drives the day-boundary rollover in
     * StreakEngine — a day with even one OVERAGE/EXTENDED breaks it.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM session WHERE endedAt >= :start AND endedAt < :end " +
            "AND outcome IS NOT NULL AND outcome != 'BEATEN')"
    )
    suspend fun hasNonBeatenSessionBetween(start: Long, end: Long): Boolean

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM session WHERE pkg = :pkg AND endedAt IS NULL")
    suspend fun clearActive(pkg: String)

    /**
     * T-111: the row survives as history — first time that's true for this
     * table. Replaces the old behavior where a session's row was always
     * deleted via clearActive() the moment it left the active set.
     */
    @Query(
        "UPDATE session SET endedAt = :endedAt, outcome = :outcome, overageS = :overageS, extensions = :extensions " +
            "WHERE id = :id"
    )
    suspend fun markEnded(id: Long, endedAt: Long, outcome: String, overageS: Int?, extensions: Int)
}
