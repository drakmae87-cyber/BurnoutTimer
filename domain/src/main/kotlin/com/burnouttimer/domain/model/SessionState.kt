package com.burnouttimer.domain.model

data class SessionState(
    val isActive: Boolean = false,
    val endsAtEpochMillis: Long = 0L
)
