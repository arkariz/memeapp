package com.arkarizdev.grudge.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * tech plan §4's `watched_app` table. T-103 seeds this with the same
 * default set the spike/T-102 used (see WatcherService.seedDefaultsIfEmpty)
 * so behavior is unchanged until T-109's app picker lets a user edit it.
 */
@Entity(tableName = "watched_app")
data class WatchedAppEntity(
    @PrimaryKey val pkg: String,
    val budgetMin: Int,
    val enabled: Boolean,
    val addedAt: Long,
)

@Dao
interface WatchedAppDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(apps: List<WatchedAppEntity>)

    @Query("SELECT * FROM watched_app WHERE enabled = 1")
    suspend fun enabled(): List<WatchedAppEntity>

    @Query("SELECT COUNT(*) FROM watched_app")
    suspend fun count(): Int
}
