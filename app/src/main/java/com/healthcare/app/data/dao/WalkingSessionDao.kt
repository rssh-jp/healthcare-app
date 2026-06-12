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

    /** サービス非稼働時に isActive=1 で残った全 stuck session を返す */
    @Query("SELECT * FROM walking_sessions WHERE isActive = 1")
    suspend fun getAllActiveSessions(): List<WalkingSession>

    @Query("SELECT * FROM walking_sessions WHERE isActive = 1 LIMIT 1")
    fun observeActiveSession(): Flow<WalkingSession?>

    @Query("SELECT * FROM walking_sessions WHERE id = :id")
    suspend fun getById(id: Long): WalkingSession?

    @Query("SELECT * FROM walking_sessions WHERE isActive = 0 ORDER BY startTime DESC")
    fun observeCompletedSessions(): Flow<List<WalkingSession>>

    @Query("DELETE FROM walking_sessions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: Collection<Long>)

    /** セッション完了: endTime・distance・calories・isActive を1クエリで原子的に更新する */
    @Query("UPDATE walking_sessions SET endTime = :endTime, totalDistanceMeters = :distance, totalCalories = :calories, isActive = 0 WHERE id = :id")
    suspend fun completeSession(id: Long, endTime: Long, distance: Double, calories: Double)

    /** 走行中の距離・カロリーのみを更新する (isActive / endTime は変更しない) */
    @Query("UPDATE walking_sessions SET totalDistanceMeters = :distance, totalCalories = :calories WHERE id = :id")
    suspend fun updateStats(id: Long, distance: Double, calories: Double)

    @Query("SELECT * FROM walking_sessions WHERE id IN (:ids)")
    suspend fun getByIds(ids: Collection<Long>): List<WalkingSession>

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

    // Sync-related queries
    @Query("SELECT * FROM walking_sessions WHERE syncStatus IN ('PENDING', 'FAILED') AND isActive = 0")
    suspend fun getPendingOrFailedSessions(): List<WalkingSession>

    @Query("UPDATE walking_sessions SET syncStatus = :status, firestoreDocId = :docId WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, status: String, docId: String?)

    @Query("SELECT * FROM walking_sessions WHERE sessionUuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): WalkingSession?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(session: WalkingSession): Long

    @Query("DELETE FROM walking_sessions WHERE isActive = 0")
    suspend fun deleteAllCompleted()

    @Query("SELECT * FROM walking_sessions WHERE isActive = 0")
    suspend fun getAllCompleted(): List<WalkingSession>

    @Query("SELECT id FROM walking_sessions WHERE isActive = 0 ORDER BY COALESCE(endTime, startTime) DESC LIMIT 1")
    suspend fun getLatestCompletedSessionId(): Long?
}

data class DailyAggregation(
    val dayTimestamp: Long,
    val totalDistance: Double,
    val totalCalories: Double,
    val sessionCount: Int,
    val totalDurationMs: Long
)
