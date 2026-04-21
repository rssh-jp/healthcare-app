package com.healthcare.app.data.repository

import com.healthcare.app.data.dao.DailyAggregation
import com.healthcare.app.data.dao.WalkingPointDao
import com.healthcare.app.data.dao.WalkingSessionDao
import com.healthcare.app.data.entity.SyncStatus
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.entity.WalkingSession
import kotlinx.coroutines.flow.Flow
import java.util.UUID
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
            sessionUuid = UUID.randomUUID().toString(),
            startTime = System.currentTimeMillis(),
            isActive = true
        )
        return sessionDao.insert(session)
    }

    suspend fun endSession(sessionId: Long, totalDistance: Double, totalCalories: Double) {
        // ターゲット UPDATE により read-modify-write 競合を回避する
        sessionDao.completeSession(
            id = sessionId,
            endTime = System.currentTimeMillis(),
            distance = totalDistance,
            calories = totalCalories
        )
    }

    suspend fun updateSessionStats(sessionId: Long, totalDistance: Double, totalCalories: Double) {
        // isActive / endTime を変更しないターゲット UPDATE
        sessionDao.updateStats(sessionId, totalDistance, totalCalories)
    }

    suspend fun getById(id: Long): WalkingSession? = sessionDao.getById(id)

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

    // Sync operations
    suspend fun updateSyncStatus(id: Long, status: SyncStatus, firestoreDocId: String?) {
        sessionDao.updateSyncStatus(id, status.name, firestoreDocId)
    }

    suspend fun getPendingSessions(): List<WalkingSession> =
        sessionDao.getPendingOrFailedSessions()

    suspend fun upsertSessionFromRemote(session: WalkingSession) {
        val existing = sessionDao.getByUuid(session.sessionUuid)
        if (existing == null) {
            sessionDao.insertIfNotExists(session)
        }
    }

    suspend fun getByUuid(uuid: String): WalkingSession? = sessionDao.getByUuid(uuid)

    // Point operations
    suspend fun addPoint(point: WalkingPoint) = pointDao.insert(point)

    suspend fun addPoints(points: List<WalkingPoint>) = pointDao.insertAll(points)

    fun getPointsBySession(sessionId: Long): Flow<List<WalkingPoint>> =
        pointDao.getPointsBySession(sessionId)

    suspend fun getPointsBySessionOnce(sessionId: Long): List<WalkingPoint> =
        pointDao.getPointsBySessionOnce(sessionId)

    suspend fun getLastPoint(sessionId: Long): WalkingPoint? =
        pointDao.getLastPoint(sessionId)

    suspend fun deleteSessionsByIds(ids: Collection<Long>) {
        // ポイントも削除
        ids.forEach { pointDao.deleteBySession(it) }
        sessionDao.deleteByIds(ids)
    }

    suspend fun getSessionsByIds(ids: Collection<Long>): List<WalkingSession> =
        sessionDao.getByIds(ids)

    suspend fun getAllCompletedSessions(): List<WalkingSession> =
        sessionDao.getAllCompleted()

    suspend fun deleteAllLocalData() {
        pointDao.deleteAll()
        sessionDao.deleteAllCompleted()
    }
}
