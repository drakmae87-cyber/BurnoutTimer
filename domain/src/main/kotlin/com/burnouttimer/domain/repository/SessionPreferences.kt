package com.burnouttimer.domain.repository

import com.burnouttimer.domain.model.SessionState
import kotlinx.coroutines.flow.Flow

interface SessionPreferences {
    val sessionState: Flow<SessionState>
    suspend fun setSessionState(state: SessionState)
}
