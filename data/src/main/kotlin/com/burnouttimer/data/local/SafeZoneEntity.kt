package com.burnouttimer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "safe_zones")
data class SafeZoneEntity(
    @PrimaryKey val id: Int = 1,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double
)

@Entity(tableName = "custom_messages")
data class CustomMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String
)

@Entity(tableName = "session_logs")
data class SessionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val event: String,
    val occurredAtEpochMillis: Long
)

@Entity(tableName = "scheduled_sessions")
data class ScheduledSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val startsAtEpochMillis: Long,
    val durationMinutes: Int,
    val isEnabled: Boolean = true
)
