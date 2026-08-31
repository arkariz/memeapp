package com.arkarizdev.bonked.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HeartbeatEntity::class,
        WatchedAppEntity::class,
        SessionEntity::class,
        RoastPayloadEntity::class,
        StreakEntity::class,
        DailyAggEntity::class,
        AnalyticsEvtEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BonkedDatabase : RoomDatabase() {
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun watchedAppDao(): WatchedAppDao
    abstract fun sessionDao(): SessionDao
    abstract fun roastPayloadDao(): RoastPayloadDao
    abstract fun streakDao(): StreakDao
    abstract fun dailyAggDao(): DailyAggDao
    abstract fun analyticsEvtDao(): AnalyticsEvtDao

    companion object {
        @Volatile private var instance: BonkedDatabase? = null

        fun get(context: Context): BonkedDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BonkedDatabase::class.java,
                    "bonked.db",
                ).build().also { instance = it }
            }
    }
}
