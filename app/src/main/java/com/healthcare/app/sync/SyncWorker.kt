package com.healthcare.app.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.healthcare.app.data.entity.SyncStatus
import com.healthcare.app.data.repository.AuthRepository
import com.healthcare.app.data.repository.FirestoreSyncRepository
import com.healthcare.app.data.repository.WalkingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val walkingRepository: WalkingRepository,
    private val firestoreSyncRepository: FirestoreSyncRepository,
    private val authRepository: AuthRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uid = authRepository.currentUser?.uid ?: return Result.success()

        val pending = walkingRepository.getPendingSessions()
        if (pending.isEmpty()) return Result.success()

        var hasFailure = false
        pending.forEach { session ->
            val points = walkingRepository.getPointsBySessionOnce(session.id)
            val result = firestoreSyncRepository.uploadSession(session, points)
            walkingRepository.updateSyncStatus(
                id = session.id,
                status = if (result.isSuccess) SyncStatus.SYNCED else SyncStatus.FAILED,
                firestoreDocId = session.sessionUuid.takeIf { result.isSuccess }
            )
            if (result.isFailure) hasFailure = true
        }

        return if (hasFailure && runAttemptCount < 5) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "pending_sync"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun buildOneTimeRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints)
                .build()
    }
}
