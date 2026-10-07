package com.burnouttimer

import android.app.Application
import android.app.AlarmManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@HiltAndroidApp
class BurnoutTimerApplication : Application()

@Module
@InstallIn(SingletonComponent::class)
object AppPlatformModule {
    @Provides
    @Singleton
    fun provideAlarmManager(@ApplicationContext context: Context): AlarmManager =
        requireNotNull(context.getSystemService(AlarmManager::class.java)) {
            "Android alarm service is unavailable."
        }

    @Provides
    @Singleton
    fun provideScheduledSessionAlarms(
        @ApplicationContext context: Context,
        alarmManager: AlarmManager
    ): ScheduledSessionAlarms = ScheduledSessionAlarms(context, alarmManager)
}
