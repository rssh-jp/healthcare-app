package com.healthcare.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.healthcare.app.data.entity.SyncStatus
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.entity.WalkingSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FirestoreSyncRepository"
private const val MAX_GEO_POINTS = 20_000

@Singleton
class FirestoreSyncRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    /**
     * 指数バックオフ付きリトライ。
     * PERMISSION_DENIED など永続的エラーは即座に返す。
     */
    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1_000L,
        block: suspend () -> Result<T>
    ): Result<T> {
        var delayMs = initialDelayMs
        var lastResult: Result<T> = Result.failure(IllegalStateException("未実行"))
        for (attempt in 1..maxAttempts) {
            lastResult = block()
            if (lastResult.isSuccess) return lastResult
            val e = lastResult.exceptionOrNull()
            if (e?.message?.contains("PERMISSION_DENIED") == true) {
                Log.w(TAG, "withRetry: PERMISSION_DENIED – skipping retry")
                return lastResult
            }
            if (attempt < maxAttempts) {
                Log.d(TAG, "withRetry: attempt $attempt/$maxAttempts failed, retrying in ${delayMs}ms", e)
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(16_000L)
            }
        }
        Log.w(TAG, "withRetry: all $maxAttempts attempt(s) exhausted")
        return lastResult
    }

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

        return withRetry {
            try {
                val sampledPoints = if (points.size > MAX_GEO_POINTS) {
                    val stride = points.size / MAX_GEO_POINTS
                    points.filterIndexed { index, _ -> index % stride == 0 }
                } else {
                    points
                }

                // Firestore はネスト配列不可のため [lat1, lng1, lat2, lng2, ...] のフラット配列で保存
                val geoFlat = ArrayList<Double>(sampledPoints.size * 2)
                for (point in sampledPoints) {
                    geoFlat.add(point.latitude)
                    geoFlat.add(point.longitude)
                }

                val doc = mapOf(
                    "sessionId" to session.sessionUuid,
                    "startTime" to session.startTime,
                    "endTime" to session.endTime,
                    "distanceMeters" to session.totalDistanceMeters,
                    "caloriesBurned" to session.totalCalories,
                    "geoFlat" to geoFlat,
                    "syncedAt" to FieldValue.serverTimestamp()
                )

                firestore
                    .collection("users/$uid/walking_sessions")
                    .document(session.sessionUuid)
                    .set(doc)
                    .await()

                Log.d(TAG, "uploadSession: success uuid=${session.sessionUuid}")
                Result.success(session.sessionUuid)
            } catch (e: Exception) {
                Log.w(TAG, "uploadSession: failed uuid=${session.sessionUuid}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * サインイン時の同期処理（双方向マージ）。
     * ローカルデータは削除しない。
     * 1. ローカルの PENDING/FAILED セッションを Firestore へアップロード
     * 2. Firestore にあってローカルにないセッションをローカルへ追加
     */
    suspend fun syncOnLogin(
        uid: String,
        walkingRepository: WalkingRepository
    ): Result<Unit> {
        return try {
            // Step 1: ローカルの未同期セッションを先にアップロード
            val localPending = walkingRepository.getPendingSessions()
            Log.d(TAG, "syncOnLogin: uploading ${localPending.size} pending session(s) for uid=$uid")
            for (session in localPending) {
                val points = walkingRepository.getPointsBySessionOnce(session.id)
                val result = uploadSession(session, points)
                if (result.isFailure) {
                    Log.w(TAG, "syncOnLogin: upload failed for session ${session.id}", result.exceptionOrNull())
                }
                walkingRepository.updateSyncStatus(
                    id = session.id,
                    status = if (result.isSuccess) SyncStatus.SYNCED else SyncStatus.FAILED,
                    firestoreDocId = session.sessionUuid.takeIf { result.isSuccess }
                )
            }

            // Step 2: Firestore にあってローカルにないセッションをダウンロードしてマージ
            val snapshot = firestore
                .collection("users/$uid/walking_sessions")
                .get()
                .await()

            for (doc in snapshot.documents) {
                val sessionUuid = doc.getString("sessionId") ?: continue
                // ローカルに既に存在する場合はスキップ（ローカルを正とする）
                if (walkingRepository.getByUuid(sessionUuid) != null) continue

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

                // geoFlat ([lat1,lng1,lat2,lng2,...]) を walking_points テーブルに復元
                val roomSession = walkingRepository.getByUuid(sessionUuid) ?: continue
                @Suppress("UNCHECKED_CAST")
                val geoFlat = doc.get("geoFlat") as? List<*> ?: emptyList<Any>()
                val walkingPoints = mutableListOf<WalkingPoint>()
                var i = 0
                while (i + 1 < geoFlat.size) {
                    val lat = (geoFlat[i] as? Number)?.toDouble()
                    val lng = (geoFlat[i + 1] as? Number)?.toDouble()
                    if (lat != null && lng != null) {
                        walkingPoints.add(
                            WalkingPoint(
                                sessionId = roomSession.id,
                                latitude = lat,
                                longitude = lng,
                                timestamp = startTime + i / 2
                            )
                        )
                    }
                    i += 2
                }
                if (walkingPoints.isNotEmpty()) {
                    walkingRepository.addPoints(walkingPoints)
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
        return withRetry {
            try {
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
}
