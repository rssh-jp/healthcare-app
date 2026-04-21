package com.healthcare.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.healthcare.app.data.repository.WalkingRepository
import com.healthcare.app.service.LocationTrackingService
import com.healthcare.app.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HealthcareApp"

@HiltAndroidApp
class HealthcareApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var walkingRepository: WalkingRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // 旧バージョンの race condition により isActive=1 のまま残ったセッションを修復する
        cleanupStuckSessions()
        // 起動時に即時同期（PENDING セッションを最速でアップロード）
        enqueuePendingSync()
        // 15 分ごとの定期同期（即時同期が失敗した場合の確実なフォールバック）
        schedulePeriodicSync()
        // アプリがバックグラウンドに移行する際にも即時同期を試みる
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                enqueuePendingSync()
            }
        })
    }

    /**
     * サービスが動いていないのに isActive=1 のまま残っているセッション（stuck session）を
     * 完了状態に修復し、次回の SyncWorker に拾わせる。
     * LIMIT 1 ではなく全件取得して複数 stuck session に対応する。
     */
    private fun cleanupStuckSessions() {
        appScope.launch {
            // isTracking が true のときはサービス稼働中なのでスキップ
            if (LocationTrackingService.isTracking.value) return@launch
            val stuckSessions = walkingRepository.getAllActiveSessions()
            if (stuckSessions.isEmpty()) return@launch

            Log.w(TAG, "cleanupStuckSessions: found ${stuckSessions.size} stuck session(s), fixing...")
            for (stuck in stuckSessions) {
                Log.w(TAG, "cleanupStuckSessions: ending stuck session id=${stuck.id}")
                walkingRepository.endSession(
                    sessionId = stuck.id,
                    totalDistance = stuck.totalDistanceMeters,
                    totalCalories = stuck.totalCalories
                )
            }
            // 全件修復後に sync を強制再スケジュール
            WorkManager.getInstance(this@HealthcareApp).enqueueUniqueWork(
                SyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                SyncWorker.buildOneTimeRequest()
            )
        }
    }

    private fun enqueuePendingSync() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            SyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            SyncWorker.buildOneTimeRequest()
        )
    }

    private fun schedulePeriodicSync() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            SyncWorker.buildPeriodicRequest()
        )
    }
}
