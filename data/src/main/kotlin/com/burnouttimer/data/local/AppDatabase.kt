package com.burnouttimer.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SafeZoneDao {
    @Query("SELECT * FROM safe_zones WHERE id = 1")
    fun observe(): Flow<SafeZoneEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(zone: SafeZoneEntity)
}

@Dao
interface CustomMessageDao {
    @Query("SELECT * FROM custom_messages ORDER BY id")
    fun observeAll(): Flow<List<CustomMessageEntity>>

    @Insert
    suspend fun insert(message: CustomMessageEntity)
}

@Dao
interface SessionLogDao {
    @Query("SELECT * FROM session_logs ORDER BY occurredAtEpochMillis DESC")
    fun observeAll(): Flow<List<SessionLogEntity>>

    @Insert
    suspend fun insert(log: SessionLogEntity)
}

@Database(
    entities = [SafeZoneEntity::class, CustomMessageEntity::class, SessionLogEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun safeZoneDao(): SafeZoneDao
    abstract fun customMessageDao(): CustomMessageDao
    abstract fun sessionLogDao(): SessionLogDao
}
