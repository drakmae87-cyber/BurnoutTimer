package com.burnouttimer.domain.repository

import com.burnouttimer.domain.model.SessionState
import com.burnouttimer.domain.model.AppLanguage
import kotlinx.coroutines.flow.Flow

interface SessionPreferences {
    val sessionState: Flow<SessionState>
    val appLanguage: Flow<AppLanguage>
    suspend fun setSessionState(state: SessionState)
    suspend fun setAppLanguage(language: AppLanguage)
}
