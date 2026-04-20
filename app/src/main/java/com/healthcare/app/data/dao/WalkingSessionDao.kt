package com.healthcare.app.data.dao

import androidx.room.*
import com.healthcare.app.data.entity.WalkingSession
import kotlinx.coroutines.flow.Flow

@Dao
interface WalkingSessionDao {
    @Insert
    suspend fun insert(session: WalkingSession): Long

    @Update
    suspend fun update(session: WalkingSession)

    @Delete
    suspend fun delete(session: WalkingSession)

    @Query("SELECT * FROM walking_sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): WalkingSession?

    @Query("SELECT * FROM walking_sessions WHERE isActive = 1 LIMIT 1")
    fun observeActiveSession(): Flow<WalkingSession?>

    @Query("SELECT * FROM walking_sessions WHERE id = :id")
    suspend fun getById(id: Long): WalkingSession?

    @Query("SELECT * FROM walking_sessions WHERE isActive = 0 ORDER BY startTime DESC")
    fun observeCompletedSessions(): Flow<List<WalkingSession>>

    @Query("SELECT * FROM walking_sessions WHERE startTime >= :startTime AND startTime < :endTime AND isActive = 0 ORDER BY startTime DESC")
    fun getSessionsByDateRange(startTime: Long, endTime: Long): Flow<List<WalkingSession>>

    @Query("SELECT COALESCE(SUM(totalDistanceMeters), 0.0) FROM walking_sessions WHERE startTime >= :startTime AND startTime < :endTime AND isActive = 0")
    fun getTotalDistanceByDateRange(startTime: Long, endTime: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(totalCalories), 0.0) FROM walking_sessions WHERE startTime >= :startTime AND startTime < :endTime AND isActive = 0")
    fun getTotalCaloriesByDateRange(startTime: Long, endTime: Long): Flow<Double>

    @Query("SELECT COUNT(*) FROM walking_sessions WHERE startTime >= :startTime AND startTime < :endTime AND isActive = 0")
    fun getSessionCountByDateRange(startTime: Long, endTime: Long): Flow<Int>

    // Daily aggregation - returns total distance and calories per day
    @Query("""
        SELECT 
            (startTime / 86400000) * 86400000 as dayTimestamp,
            SUM(totalDistanceMeters) as totalDistance,
            SUM(totalCalories) as totalCalories,
            COUNT(*) as sessionCount,
            SUM(CASE WHEN endTime IS NOT NULL THEN endTime - startTime ELSE 0 END) as totalDurationMs
        FROM walking_sessions 
        WHERE startTime >= :startTime AND startTime < :endTime AND isActive = 0
        GROUP BY (startTime / 86400000)
        ORDER BY dayTimestamp DESC
    """)
    fun getDailyAggregation(startTime: Long, endTime: Long): Flow<List<DailyAggregation>>
}

data class DailyAggregation(
    val dayTimestamp: Long,
    val totalDistance: Double,
    val totalCalories: Double,
    val sessionCount: Int,
    val totalDurationMs: Long
)
