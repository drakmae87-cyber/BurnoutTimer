package com.burnouttimer.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.burnouttimer.domain.model.SessionState
import com.burnouttimer.domain.model.AppLanguage
import com.burnouttimer.domain.repository.SessionPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore(name = "session_preferences")

class DataStoreSessionPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) : SessionPreferences {
    override val sessionState: Flow<SessionState> = context.sessionDataStore.data.map { preferences ->
        SessionState(
            isActive = preferences[IS_SESSION_ACTIVE] ?: false,
            endsAtEpochMillis = preferences[SESSION_END_TIME] ?: 0L
        )
    }

    override val appLanguage: Flow<AppLanguage> = context.sessionDataStore.data.map { preferences ->
        when (preferences[APP_LANGUAGE]) {
            LANGUAGE_ENGLISH -> AppLanguage.ENGLISH
            else -> AppLanguage.SPANISH
        }
    }

    override suspend fun setSessionState(state: SessionState) {
        context.sessionDataStore.edit { preferences ->
            preferences[IS_SESSION_ACTIVE] = state.isActive
            preferences[SESSION_END_TIME] = state.endsAtEpochMillis
        }
    }

    override suspend fun setAppLanguage(language: AppLanguage) {
        context.sessionDataStore.edit { preferences ->
            preferences[APP_LANGUAGE] = if (language == AppLanguage.ENGLISH) LANGUAGE_ENGLISH else LANGUAGE_SPANISH
        }
    }

    private companion object {
        val IS_SESSION_ACTIVE = booleanPreferencesKey("is_session_active")
        val SESSION_END_TIME = longPreferencesKey("session_end_time")
        val APP_LANGUAGE = androidx.datastore.preferences.core.stringPreferencesKey("app_language")
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_SPANISH = "es"
    }
}
