package com.healthcare.app.data.dao

import androidx.room.*
import com.healthcare.app.data.entity.WalkingPoint
import kotlinx.coroutines.flow.Flow

@Dao
interface WalkingPointDao {
    @Insert
    suspend fun insert(point: WalkingPoint)

    @Insert
    suspend fun insertAll(points: List<WalkingPoint>)

    @Query("SELECT * FROM walking_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getPointsBySession(sessionId: Long): Flow<List<WalkingPoint>>

    @Query("SELECT * FROM walking_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getPointsBySessionOnce(sessionId: Long): List<WalkingPoint>

    @Query("SELECT * FROM walking_points WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPoint(sessionId: Long): WalkingPoint?

    @Query("DELETE FROM walking_points WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
