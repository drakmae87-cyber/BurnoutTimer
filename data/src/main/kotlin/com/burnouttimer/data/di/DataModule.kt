package com.burnouttimer.data.di

import android.content.Context
import androidx.room.Room
import com.burnouttimer.data.local.AppDatabase
import com.burnouttimer.data.local.ScheduledSessionDao
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scheduled_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    startsAtEpochMillis INTEGER NOT NULL,
                    durationMinutes INTEGER NOT NULL,
                    isEnabled INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider
    ): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "burnout_timer.db")
        .openHelperFactory(SupportFactory(passphraseProvider.getPassphrase()))
        .addMigrations(MIGRATION_1_2)
        .build()

    @Provides
    fun provideScheduledSessionDao(database: AppDatabase): ScheduledSessionDao =
        database.scheduledSessionDao()
}
