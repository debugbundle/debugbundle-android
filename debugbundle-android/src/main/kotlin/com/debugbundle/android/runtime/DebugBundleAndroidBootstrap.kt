package com.debugbundle.android.runtime

import android.app.Application
import com.debugbundle.android.DebugBundleClient
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.DebugBundleDeviceContextProvider
import com.debugbundle.android.DebugBundleQueueStore
import com.debugbundle.android.DebugBundleRemoteConfigClient
import com.debugbundle.android.DebugBundleTransport
import com.debugbundle.android.FileDebugBundleQueueStore
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService

object DebugBundleAndroidBootstrap {
    @JvmStatic
    fun createClient(
        application: Any,
        config: DebugBundleConfig,
        transport: DebugBundleTransport,
        remoteConfigClient: DebugBundleRemoteConfigClient,
        queueStore: DebugBundleQueueStore?,
        deviceContextProvider: DebugBundleDeviceContextProvider?,
        runtimeRegistration: AutoCloseable?,
        clock: () -> Instant,
        random: () -> Double,
        executor: ScheduledExecutorService,
    ): DebugBundleClient {
        val androidApplication = application as? Application
            ?: return DebugBundleClient.createWithoutAndroidBootstrap(
                application = application,
                config = config,
                transport = transport,
                remoteConfigClient = remoteConfigClient,
                queueStore = queueStore,
                deviceContextProvider = deviceContextProvider,
                runtimeRegistration = runtimeRegistration,
                clock = clock,
                random = random,
                executor = executor,
            )

        val resolvedConfig = DebugBundleAndroidDefaults.resolveConfig(androidApplication, config)
        val resolvedQueueStore = queueStore ?: FileDebugBundleQueueStore(resolvedConfig.offlineQueuePath!!)
        val resolvedDeviceContextProvider = deviceContextProvider ?: AndroidDebugBundleDeviceContextProvider(
            androidApplication,
            resolvedConfig,
        )
        val scheduler: DebugBundleBackgroundFlushScheduler =
            if (queueStore == null && resolvedConfig.enabled && resolvedConfig.projectToken.isNotBlank()) {
                DebugBundleWorkManagerScheduler(androidApplication)
            } else {
                NoopDebugBundleBackgroundFlushScheduler
            }

        val client = DebugBundleClient.createWithoutAndroidBootstrap(
            application = androidApplication,
            config = resolvedConfig,
            transport = transport,
            remoteConfigClient = remoteConfigClient,
            queueStore = resolvedQueueStore,
            deviceContextProvider = resolvedDeviceContextProvider,
            runtimeRegistration = runtimeRegistration,
            clock = clock,
            random = random,
            executor = executor,
        )
        client.registerRuntimeCloseable(DebugBundleActivityLifecycleInstaller(androidApplication, client.lifecycle))
        client.registerRuntimeCloseable(DebugBundleProcessLifecycleInstaller(client.lifecycle, scheduler))

        if (resolvedConfig.enabled && resolvedConfig.projectToken.isNotBlank()) {
            val configStore = DebugBundleAndroidConfigStore(androidApplication)
            configStore.save(resolvedConfig)
            if (DebugBundleApplicationExitInfoReporter(androidApplication, configStore).capturePendingExitInfo(client)) {
                scheduler.scheduleImmediate()
            }
            scheduler.ensureScheduled()
        }

        return client
    }
}
