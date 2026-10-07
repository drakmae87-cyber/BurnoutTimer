package com.burnouttimer.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.burnouttimer.domain.model.ScheduledSession
import com.burnouttimer.domain.schedule.ScheduleRules
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

@Dao
interface ScheduledSessionDao {
    @Query("SELECT * FROM scheduled_sessions WHERE isEnabled = 1 ORDER BY startsAtEpochMillis")
    fun observeAll(): Flow<List<ScheduledSessionEntity>>

    @Query("SELECT * FROM scheduled_sessions WHERE isEnabled = 1")
    suspend fun getEnabled(): List<ScheduledSessionEntity>

    @Insert
    suspend fun insert(session: ScheduledSessionEntity): Long

    @Transaction
    suspend fun insertIfNoOverlap(session: ScheduledSessionEntity): Long {
        val proposed = session.toDomain()
        val existing = getEnabled().map(ScheduledSessionEntity::toDomain)
        if (ScheduleRules.hasOverlap(proposed, existing)) return -1L
        return insert(session)
    }

    @Query("SELECT * FROM scheduled_sessions WHERE id = :id")
    suspend fun getById(id: Long): ScheduledSessionEntity?

    @Query("SELECT * FROM scheduled_sessions WHERE isEnabled = 1 AND startsAtEpochMillis >= :now ORDER BY startsAtEpochMillis")
    suspend fun getUpcoming(now: Long): List<ScheduledSessionEntity>

    @Query("DELETE FROM scheduled_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

private fun ScheduledSessionEntity.toDomain() = ScheduledSession(
    id = id,
    title = title,
    startsAtEpochMillis = startsAtEpochMillis,
    durationMinutes = durationMinutes
)

@Database(
    entities = [
        SafeZoneEntity::class,
        CustomMessageEntity::class,
        SessionLogEntity::class,
        ScheduledSessionEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun safeZoneDao(): SafeZoneDao
    abstract fun customMessageDao(): CustomMessageDao
    abstract fun sessionLogDao(): SessionLogDao
    abstract fun scheduledSessionDao(): ScheduledSessionDao
}
