package com.arkarizdev.bonked.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Singleton row (id is always 0) — tech plan §4's `heartbeat` table. */
@Entity(tableName = "heartbeat")
data class HeartbeatEntity(
    @PrimaryKey val id: Int = 0,
    val lastTickAt: Long,
    val serviceStartedAt: Long,
)

@Dao
interface HeartbeatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HeartbeatEntity)

    @Query("SELECT * FROM heartbeat WHERE id = 0")
    suspend fun get(): HeartbeatEntity?
}
