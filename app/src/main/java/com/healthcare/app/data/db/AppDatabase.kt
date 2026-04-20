package com.healthcare.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.healthcare.app.data.dao.WalkingPointDao
import com.healthcare.app.data.dao.WalkingSessionDao
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.entity.WalkingSession

@Database(
    entities = [WalkingSession::class, WalkingPoint::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun walkingSessionDao(): WalkingSessionDao
    abstract fun walkingPointDao(): WalkingPointDao
}
