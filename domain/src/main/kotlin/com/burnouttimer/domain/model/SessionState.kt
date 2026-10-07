package com.burnouttimer.domain.model

data class SessionState(
    val isActive: Boolean = false,
    val endsAtEpochMillis: Long = 0L
)

enum class AppLanguage {
    SPANISH,
    ENGLISH
}

data class ScheduledSession(
    val id: Long = 0L,
    val title: String,
    val startsAtEpochMillis: Long,
    val durationMinutes: Int
) {
    init {
        require(title.isNotBlank()) { "Scheduled session title must not be blank." }
        require(startsAtEpochMillis > 0L) { "Scheduled session start time must be positive." }
        require(durationMinutes in 1..1_440) { "Scheduled session duration must be between 1 and 1440 minutes." }
    }
}
