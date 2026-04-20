package com.healthcare.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
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
        schedulePeriodicSync()
    }

    private fun schedulePeriodicSync() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            SyncWorker.buildPeriodicRequest()
        )
    }
}
