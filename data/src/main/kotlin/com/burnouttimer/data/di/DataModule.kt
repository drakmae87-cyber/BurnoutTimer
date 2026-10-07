package com.burnouttimer.data.di

import android.content.Context
import androidx.room.Room
import com.burnouttimer.data.local.AppDatabase
import com.burnouttimer.data.preferences.DataStoreSessionPreferences
import com.burnouttimer.data.security.DatabasePassphraseProvider
import com.burnouttimer.domain.repository.SessionPreferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.sqlcipher.database.SupportFactory

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds
    @Singleton
    abstract fun bindSessionPreferences(implementation: DataStoreSessionPreferences): SessionPreferences
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvidersModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider
    ): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "burnout_timer.db")
        .openHelperFactory(SupportFactory(passphraseProvider.getPassphrase()))
        .build()
}
