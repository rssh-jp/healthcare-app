package com.healthcare.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.healthcare.app.data.entity.SyncStatus
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.entity.WalkingSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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
     * WalkingPoint リストを ByteBuffer に詰め GZIP 圧縮した Firestore Blob を返す。
     * 1点あたり lat(8B) + lng(8B) = 16B → GZIP で約 40〜60% 削減。
     */
    internal fun encodeGeoBlob(points: List<WalkingPoint>): Blob {
        val buf = ByteBuffer.allocate(points.size * 16)
        for (p in points) {
            buf.putDouble(p.latitude)
            buf.putDouble(p.longitude)
        }
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(buf.array()) }
        return Blob.fromBytes(bos.toByteArray())
    }

    /**
     * GZIP 圧縮済み Blob から座標ペアリストを復元する。
     */
    internal fun decodeGeoBlob(blob: Blob): List<Pair<Double, Double>> {
        val raw = GZIPInputStream(ByteArrayInputStream(blob.toBytes())).use { it.readBytes() }
        val buf = ByteBuffer.wrap(raw)
        val result = mutableListOf<Pair<Double, Double>>()
        while (buf.remaining() >= 16) {
            result.add(buf.getDouble() to buf.getDouble())
        }
        return result
    }

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
                    val stride = (points.size + MAX_GEO_POINTS - 1) / MAX_GEO_POINTS
                    points.filterIndexed { index, _ -> index % stride == 0 }
                } else {
                    points
                }

                val doc = mapOf(
                    "sessionId" to session.sessionUuid,
                    "startTime" to session.startTime,
                    "endTime" to session.endTime,
                    "distanceMeters" to session.totalDistanceMeters,
                    "caloriesBurned" to session.totalCalories,
                    "geoFlatBlob" to encodeGeoBlob(sampledPoints),
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

                // 座標データを復元（geoFlatBlob 優先、旧形式 geoFlat にフォールバック）
                val roomSession = walkingRepository.getByUuid(sessionUuid) ?: continue
                val coordPairs: List<Pair<Double, Double>> = try {
                    val blob = doc.getBlob("geoFlatBlob")
                    if (blob != null) {
                        decodeGeoBlob(blob)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val geoFlat = doc.get("geoFlat") as? List<*> ?: emptyList<Any>()
                        val pairs = mutableListOf<Pair<Double, Double>>()
                        var i = 0
                        while (i + 1 < geoFlat.size) {
                            val lat = (geoFlat[i] as? Number)?.toDouble()
                            val lng = (geoFlat[i + 1] as? Number)?.toDouble()
                            if (lat != null && lng != null) pairs.add(lat to lng)
                            i += 2
                        }
                        pairs
                    }
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "syncOnLogin: failed to decode geoFlatBlob for doc=${doc.id}", e)
                    emptyList()
                }
                val walkingPoints = coordPairs.mapIndexed { idx, (lat, lng) ->
                    WalkingPoint(
                        sessionId = roomSession.id,
                        latitude = lat,
                        longitude = lng,
                        timestamp = startTime + idx
                    )
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
