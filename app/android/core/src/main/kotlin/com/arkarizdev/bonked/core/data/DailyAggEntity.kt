package com.arkarizdev.bonked.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * tech plan §4's `daily_agg` table (date+pkg composite key). Schema only —
 * no writer until the analytics rollup that feeds T-204's cohort dashboard.
 */
@Entity(tableName = "daily_agg", primaryKeys = ["date", "pkg"])
data class DailyAggEntity(
    val date: String, // ISO date
    val pkg: String,
    val usedMin: Int,
    val overageMin: Int,
)

@Dao
interface DailyAggDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyAggEntity)

    @Query("SELECT * FROM daily_agg WHERE date = :date")
    suspend fun forDate(date: String): List<DailyAggEntity>
}
