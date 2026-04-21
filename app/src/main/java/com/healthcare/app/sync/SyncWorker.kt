package com.healthcare.app.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.healthcare.app.data.entity.SyncStatus
import com.healthcare.app.data.repository.AuthRepository
import com.healthcare.app.data.repository.FirestoreSyncRepository
import com.healthcare.app.data.repository.WalkingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

private const val TAG = "SyncWorker"

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val walkingRepository: WalkingRepository,
    private val firestoreSyncRepository: FirestoreSyncRepository,
    private val authRepository: AuthRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uid = authRepository.currentUser?.uid
        if (uid == null) {
            Log.d(TAG, "doWork: user not authenticated, skipping")
            return Result.success()
        }

        val pending = walkingRepository.getPendingSessions()
        if (pending.isEmpty()) {
            Log.d(TAG, "doWork: no pending sessions")
            return Result.success()
        }

        Log.d(TAG, "doWork: uploading ${pending.size} session(s) for uid=$uid")
        var hasFailure = false
        for (session in pending) {
            val points = walkingRepository.getPointsBySessionOnce(session.id)
            val result = firestoreSyncRepository.uploadSession(session, points)
            if (result.isSuccess) {
                Log.d(TAG, "doWork: session ${session.id} (uuid=${session.sessionUuid}) uploaded")
            } else {
                val ex = result.exceptionOrNull()
                if (ex?.message?.contains("PERMISSION_DENIED") == true) {
                    Log.e(TAG, "doWork: PERMISSION_DENIED – Firestore rules are blocking writes. " +
                        "Deploy firestore.rules via Firebase Console or `firebase deploy --only firestore:rules`.")
                    // ルール未設定はリトライしても無駄なので即終了
                    walkingRepository.updateSyncStatus(
                        id = session.id,
                        status = SyncStatus.FAILED,
                        firestoreDocId = null
                    )
                    return Result.failure()
                }
                Log.w(TAG, "doWork: session ${session.id} upload failed", ex)
                hasFailure = true
            }
            walkingRepository.updateSyncStatus(
                id = session.id,
                status = if (result.isSuccess) SyncStatus.SYNCED else SyncStatus.FAILED,
                firestoreDocId = session.sessionUuid.takeIf { result.isSuccess }
            )
        }

        return if (hasFailure && runAttemptCount < 5) {
            Log.w(TAG, "doWork: retrying (attempt $runAttemptCount)")
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "pending_sync"
        const val PERIODIC_WORK_NAME = "periodic_sync"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun buildOneTimeRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints)
                .build()

        fun buildPeriodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
    }
}
