package com.arkarizdev.bonked.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Singleton row (id is always 1, matching tech plan §4's `streak` table).
 * Schema only — no writer until T-201 (streak engine).
 */
@Entity(tableName = "streak")
data class StreakEntity(
    @PrimaryKey val id: Int = 1,
    val current: Int,
    val best: Int,
    val lastCountedDay: String, // ISO date, e.g. "2026-08-19"
)

@Dao
interface StreakDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StreakEntity)

    @Query("SELECT * FROM streak WHERE id = 1")
    suspend fun get(): StreakEntity?
}
