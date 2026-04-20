package com.healthcare.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.healthcare.app.data.dao.WalkingPointDao
import com.healthcare.app.data.dao.WalkingSessionDao
import com.healthcare.app.data.db.AppDatabase
import com.healthcare.app.data.db.MIGRATION_1_2
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "healthcare_db"
        )
            .addMigrations(MIGRATION_1_2)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            }).build()
    }

    @Provides
    fun provideWalkingSessionDao(database: AppDatabase): WalkingSessionDao {
        return database.walkingSessionDao()
    }

    @Provides
    fun provideWalkingPointDao(database: AppDatabase): WalkingPointDao {
        return database.walkingPointDao()
    }
}
