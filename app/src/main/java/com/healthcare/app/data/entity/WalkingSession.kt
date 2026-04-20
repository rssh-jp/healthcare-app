package com.healthcare.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "walking_sessions")
data class WalkingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,         // epoch millis
    val endTime: Long? = null,   // null while active
    val totalDistanceMeters: Double = 0.0,
    val totalCalories: Double = 0.0,
    val isActive: Boolean = true
)
