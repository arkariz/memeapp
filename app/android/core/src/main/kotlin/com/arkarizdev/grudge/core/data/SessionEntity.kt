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
    val outcome: String? = null, // TODO(T-111): BEATEN | OVERAGE | ABANDONED
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

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM session WHERE pkg = :pkg AND endedAt IS NULL")
    suspend fun clearActive(pkg: String)
}
