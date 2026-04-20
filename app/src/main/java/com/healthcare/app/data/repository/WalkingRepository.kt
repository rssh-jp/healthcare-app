package com.healthcare.app.data.repository

import com.healthcare.app.data.dao.DailyAggregation
import com.healthcare.app.data.dao.WalkingPointDao
import com.healthcare.app.data.dao.WalkingSessionDao
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.entity.WalkingSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalkingRepository @Inject constructor(
    private val sessionDao: WalkingSessionDao,
    private val pointDao: WalkingPointDao
) {
    // Session operations
    suspend fun startNewSession(): Long {
        val session = WalkingSession(
            startTime = System.currentTimeMillis(),
            isActive = true
        )
        return sessionDao.insert(session)
    }

    suspend fun endSession(sessionId: Long, totalDistance: Double, totalCalories: Double) {
        val session = sessionDao.getById(sessionId) ?: return
        sessionDao.update(
            session.copy(
                endTime = System.currentTimeMillis(),
                totalDistanceMeters = totalDistance,
                totalCalories = totalCalories,
                isActive = false
            )
        )
    }

    suspend fun updateSessionStats(sessionId: Long, totalDistance: Double, totalCalories: Double) {
        val session = sessionDao.getById(sessionId) ?: return
        sessionDao.update(
            session.copy(
                totalDistanceMeters = totalDistance,
                totalCalories = totalCalories
            )
        )
    }

    suspend fun getActiveSession(): WalkingSession? = sessionDao.getActiveSession()

    fun observeActiveSession(): Flow<WalkingSession?> = sessionDao.observeActiveSession()

    fun observeCompletedSessions(): Flow<List<WalkingSession>> = sessionDao.observeCompletedSessions()

    fun getSessionsByDateRange(startTime: Long, endTime: Long): Flow<List<WalkingSession>> =
        sessionDao.getSessionsByDateRange(startTime, endTime)

    fun getTotalDistanceByDateRange(startTime: Long, endTime: Long): Flow<Double> =
        sessionDao.getTotalDistanceByDateRange(startTime, endTime)

    fun getTotalCaloriesByDateRange(startTime: Long, endTime: Long): Flow<Double> =
        sessionDao.getTotalCaloriesByDateRange(startTime, endTime)

    fun getSessionCountByDateRange(startTime: Long, endTime: Long): Flow<Int> =
        sessionDao.getSessionCountByDateRange(startTime, endTime)

    fun getDailyAggregation(startTime: Long, endTime: Long): Flow<List<DailyAggregation>> =
        sessionDao.getDailyAggregation(startTime, endTime)

    // Point operations
    suspend fun addPoint(point: WalkingPoint) = pointDao.insert(point)

    fun getPointsBySession(sessionId: Long): Flow<List<WalkingPoint>> =
        pointDao.getPointsBySession(sessionId)

    suspend fun getPointsBySessionOnce(sessionId: Long): List<WalkingPoint> =
        pointDao.getPointsBySessionOnce(sessionId)

    suspend fun getLastPoint(sessionId: Long): WalkingPoint? =
        pointDao.getLastPoint(sessionId)
}
