package com.debugbundle.android.runtime

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal class DebugBundleWorkManagerScheduler(
    application: Application,
) : DebugBundleBackgroundFlushScheduler {
    private val workManager = WorkManager.getInstance(application)

    override fun ensureScheduled() {
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DebugBundleFlushWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .addTag(WORK_TAG)
                .build(),
        )
    }

    override fun scheduleImmediate() {
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DebugBundleFlushWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(networkConstraints())
                .addTag(WORK_TAG)
                .build(),
        )
    }

    private fun networkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    internal companion object {
        const val WORK_TAG = "debugbundle-flush"
        const val IMMEDIATE_WORK_NAME = "debugbundle-flush-now"
        const val PERIODIC_WORK_NAME = "debugbundle-flush-periodic"
    }
}
