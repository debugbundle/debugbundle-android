package com.debugbundle.android.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.debugbundle.android.DebugBundleClient
import com.debugbundle.android.DebugBundleHttpRemoteConfigClient
import com.debugbundle.android.DebugBundleHttpTransport
import com.debugbundle.android.DebugBundleDeviceContextProvider
import com.debugbundle.android.DebugBundleRemoteConfigClient
import com.debugbundle.android.DebugBundleTransport
import com.debugbundle.android.FileDebugBundleQueueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DebugBundleFlushWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val application = applicationContext as? android.app.Application ?: return@withContext Result.success()
        DebugBundleFlushWorkerRunner(
            application = application,
            dispatcher = Dispatchers.IO,
        ).run()
    }
}

internal class DebugBundleFlushWorkerRunner(
    private val application: android.app.Application,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val configStore: DebugBundleAndroidConfigStore = DebugBundleAndroidConfigStore(application),
    private val transportFactory: () -> DebugBundleTransport = {
        DebugBundleAndroidRuntimeOverrides.transportFactory?.invoke() ?: DebugBundleHttpTransport()
    },
    private val remoteConfigClientFactory: () -> DebugBundleRemoteConfigClient = {
        DebugBundleAndroidRuntimeOverrides.remoteConfigClientFactory?.invoke() ?: DebugBundleHttpRemoteConfigClient()
    },
    private val deviceContextProviderFactory: (android.app.Application, com.debugbundle.android.DebugBundleConfig) -> DebugBundleDeviceContextProvider =
        { app, config ->
            DebugBundleAndroidRuntimeOverrides.deviceContextProviderFactory?.invoke(app, config)
                ?: AndroidDebugBundleDeviceContextProvider(app, config)
        },
) {
    suspend fun run(): ListenableWorker.Result = withContext(dispatcher) {
        val persistedConfig = configStore.load() ?: return@withContext ListenableWorker.Result.success()
        val resolvedConfig = DebugBundleAndroidDefaults.resolveConfig(application, persistedConfig).copy(
            captureFatalExceptions = false,
        )
        val queueStore = FileDebugBundleQueueStore(resolvedConfig.offlineQueuePath!!)
        val limits = DebugBundleAndroidDefaults.queueLimits(resolvedConfig)
        val beforeCount = queueStore.snapshot(System.currentTimeMillis(), limits).size
        if (beforeCount == 0) {
            return@withContext ListenableWorker.Result.success()
        }

        val client = DebugBundleClient.createWithoutAndroidBootstrap(
            application = application,
            config = resolvedConfig,
            transport = transportFactory(),
            remoteConfigClient = remoteConfigClientFactory(),
            queueStore = queueStore,
            deviceContextProvider = deviceContextProviderFactory(application, resolvedConfig),
        )
        client.flush(resolvedConfig.requestTimeout)
        client.close()

        val afterCount = queueStore.snapshot(System.currentTimeMillis(), limits).size
        if (afterCount == 0) ListenableWorker.Result.success() else ListenableWorker.Result.retry()
    }
}

internal object DebugBundleAndroidRuntimeOverrides {
    @Volatile
    var transportFactory: (() -> DebugBundleTransport)? = null

    @Volatile
    var remoteConfigClientFactory: (() -> DebugBundleRemoteConfigClient)? = null

    @Volatile
    var deviceContextProviderFactory: ((android.app.Application, com.debugbundle.android.DebugBundleConfig) -> DebugBundleDeviceContextProvider)? =
        null
}
