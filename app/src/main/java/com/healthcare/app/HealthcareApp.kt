package com.healthcare.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.healthcare.app.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

// TODO: google-services.json を実際の Firebase プロジェクトのものに差し替えてください。
//       app/google-services.json.example を参照し、Firebase コンソールからダウンロードした
//       google-services.json を app/ ディレクトリに配置してください。

@HiltAndroidApp
class HealthcareApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
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
