package app.locationjournal.di

import android.content.Context
import androidx.room.Room
import app.locationjournal.data.AppPreferences
import app.locationjournal.data.JournalDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JournalDatabase =
        Room.databaseBuilder(context, JournalDatabase::class.java, "journal.db")
            .addMigrations(JournalDatabase.MIGRATION_1_2)
            .build()

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context) = AppPreferences(context)
}
