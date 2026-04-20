package com.healthcare.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.healthcare.app.data.entity.SyncStatus
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.entity.WalkingSession
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_GEO_POINTS = 20_000

@Singleton
class FirestoreSyncRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    /**
     * WalkingSession と対応する WalkingPoint を Firestore にアップロードする。
     * 20,000 点を超える場合は均等間引きを行う。
     * @return 成功時はドキュメント ID（= sessionUuid）を含む Result
     */
    suspend fun uploadSession(
        session: WalkingSession,
        points: List<WalkingPoint>
    ): Result<String> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("ユーザーが未認証です"))

        return try {
            val sampledPoints = if (points.size > MAX_GEO_POINTS) {
                val stride = points.size / MAX_GEO_POINTS
                points.filterIndexed { index, _ -> index % stride == 0 }
            } else {
                points
            }

            val geoPoints = sampledPoints.map { point ->
                mapOf("lat" to point.latitude, "lng" to point.longitude)
            }

            val doc = mapOf(
                "sessionId" to session.sessionUuid,
                "startTime" to session.startTime,
                "endTime" to session.endTime,
                "distanceMeters" to session.totalDistanceMeters,
                "caloriesBurned" to session.totalCalories,
                "geoPoints" to geoPoints,
                "syncedAt" to FieldValue.serverTimestamp()
            )

            firestore
                .collection("users/$uid/walking_sessions")
                .document(session.sessionUuid)
                .set(doc)
                .await()

            Result.success(session.sessionUuid)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * サインイン時の同期処理。
     * - Firestore にデータがない（初回ログイン）: ローカルデータを全件アップロード
     * - Firestore にデータがある（再ログイン）: Firestore のデータでローカルを完全上書き
     */
    suspend fun syncOnLogin(
        uid: String,
        walkingRepository: WalkingRepository
    ): Result<Unit> {
        return try {
            val snapshot = firestore
                .collection("users/$uid/walking_sessions")
                .get()
                .await()

            if (snapshot.isEmpty) {
                // 初回ログイン: ローカルデータを全件アップロード
                val localSessions = walkingRepository.getAllCompletedSessions()
                for (session in localSessions) {
                    val points = walkingRepository.getPointsBySessionOnce(session.id)
                    val result = uploadSession(session, points)
                    walkingRepository.updateSyncStatus(
                        id = session.id,
                        status = if (result.isSuccess) SyncStatus.SYNCED else SyncStatus.FAILED,
                        firestoreDocId = session.sessionUuid.takeIf { result.isSuccess }
                    )
                }
            } else {
                // 再ログイン: Firestore のデータでローカルを完全上書き
                walkingRepository.deleteAllLocalData()
                for (doc in snapshot.documents) {
                    val sessionUuid = doc.getString("sessionId") ?: continue
                    val startTime = doc.getLong("startTime") ?: continue
                    val endTime = doc.getLong("endTime")
                    val distanceMeters = doc.getDouble("distanceMeters") ?: 0.0
                    val calories = doc.getDouble("caloriesBurned") ?: 0.0

                    val session = WalkingSession(
                        sessionUuid = sessionUuid,
                        startTime = startTime,
                        endTime = endTime,
                        totalDistanceMeters = distanceMeters,
                        totalCalories = calories,
                        isActive = false,
                        syncStatus = SyncStatus.SYNCED,
                        firestoreDocId = doc.id
                    )
                    walkingRepository.upsertSessionFromRemote(session)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * @deprecated syncOnLogin を使用してください
     */
    suspend fun fetchAndMerge(
        uid: String,
        walkingRepository: WalkingRepository
    ): Result<Unit> {
        return try {
            val snapshot = firestore
                .collection("users/$uid/walking_sessions")
                .get()
                .await()

            for (doc in snapshot.documents) {
                val sessionUuid = doc.getString("sessionId") ?: continue
                val startTime = doc.getLong("startTime") ?: continue
                val endTime = doc.getLong("endTime")
                val distanceMeters = doc.getDouble("distanceMeters") ?: 0.0
                val calories = doc.getDouble("caloriesBurned") ?: 0.0

                val session = WalkingSession(
                    sessionUuid = sessionUuid,
                    startTime = startTime,
                    endTime = endTime,
                    totalDistanceMeters = distanceMeters,
                    totalCalories = calories,
                    isActive = false,
                    syncStatus = SyncStatus.SYNCED,
                    firestoreDocId = doc.id
                )

                walkingRepository.upsertSessionFromRemote(session)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Firestore から指定セッションを削除する。
     * 未サインインの場合はスキップ（ローカル削除のみ）。
     */
    suspend fun deleteSessions(sessionUuids: Collection<String>): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        return try {
            val batch = firestore.batch()
            sessionUuids.forEach { uuid ->
                val ref = firestore
                    .collection("users/$uid/walking_sessions")
                    .document(uuid)
                batch.delete(ref)
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
